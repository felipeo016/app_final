# Arquitectura de Unicanal (Fase 1, MVP)

Este documento resume cómo está construido Unicanal. Las reglas detalladas y las decisiones aprobadas
una por una están en [`.github/copilot-instructions.md`](../.github/copilot-instructions.md), que es la
fuente de verdad; aquí se explica el conjunto.

## Visión general

```
 Navegador (React)                     Backend (Spring Boot, un solo proceso)                MongoDB
 ─────────────────                     ─────────────────────────────────────                 ───────
 Bandeja · Chat · Perfil ── HTTP ──▶  /api/**  (JWT)  ─▶ servicios ─────────────────────▶  tenants, users,
          ▲                                                    │                             contacts,
          └──── WebSocket STOMP ◀──  /ws  ◀── RealtimePublisher ◀── eventos de Spring       conversations,
                (/topic/tenants/{id}/events)                   ▲                             messages,
                                                               │                             quickReplies
 Meta (WhatsApp Cloud API) ── webhook firmado ─▶ /api/webhooks/whatsapp ─▶ WebhookService
                           ◀── Graph API (envío) ── WhatsAppCloudAdapter
```

- **Monolito modular**: un único backend desplegable, organizado en paquetes por módulo.
- **Multi-tenant**: cada documento lleva `tenantId`, que siempre sale del JWT (nunca del body ni de
  la URL) y filtra todas las consultas. La única excepción es el webhook, que no trae JWT: su empresa
  sale de la cuenta del canal (`TenantResolver`).
- **Todos los ids son String** en MongoDB (nunca ObjectId), verificado por `IdTypesIntegrationTest`.

## Módulos del backend (`com.unicanal`)

| Paquete | Responsabilidad |
|---|---|
| `auth` | Registro e inicio de sesión, JWT HS256 (`JwtService`), `PrincipalResolver`: la validación de identidad que comparten HTTP (`JwtAuthFilter`) y WebSocket. Comprueba en base de datos que el usuario siga activo y sea del tenant del token. |
| `tenant` | Empresa y su configuración (`riskAlertMinutes`, modo de IA e información del negocio). |
| `ai` | IA local: `AiProvider` (como `ChannelAdapter`) con `OllamaProvider` (`/api/chat`, `keep_alive`, precarga al arrancar), instrucciones y validación del JSON (`AiPrompts`, `AiAnswer`), chequeos fijos (`HumanTopics`, `CatalogGuard`), sugerencias, selección de respuesta rápida y estado (`AiAssistant`) y respuesta automática en hilos propios (`AutoReplyService`). |
| `user` | Usuarios (OWNER, AGENT), alta y desactivación de agentes, usuarios asignables. |
| `contact` | Clientes con una identidad por canal (`identityKeys` "CANAL:externalId", índice único por tenant). |
| `conversation` | Bandeja, estados, asignación (`AssignmentService`, también por turnos), respuestas (`ReplyService`), respuestas rápidas, ventas y alerta de riesgo (`RiskAlertService`). |
| `message` | Mensajes y sus eventos. |
| `channel` | Abstracción `ChannelAdapter` (WhatsApp Cloud API y simulado), `ChannelRegistry`, procesamiento de mensajes entrantes (`InboundMessageProcessor`), webhooks (`WebhookService`) y estados de entrega (`DeliveryStatusUpdater`). |
| `realtime` | STOMP sobre WebSocket: autenticación del CONNECT, tópico por empresa, difusión de eventos y cierre de sesiones. |
| `demo` | `DevDataSeeder`: datos de demostración (solo perfil `dev` y `SEED_DEMO=true`). |
| `common`, `config` | Errores (`ApiException`, `GlobalExceptionHandler`), seguridad, reloj (`Clock`), tareas programadas. |

## Frontend (`frontend/src`)

| Carpeta | Responsabilidad |
|---|---|
| `auth`, `api` | Sesión en `sessionStorage`, cliente HTTP (un 401 cierra la sesión), traducción de errores a español. |
| `inbox` | Bandeja, filtros y estado en tiempo real (`useInbox`, `inboxReducer`). |
| `conversation` | Chat abierto (`useConversation`), mensajes, caja de respuesta y respuestas rápidas. |
| `profile` | Perfil del cliente, estado, asignación y venta. |
| `realtime` | Cliente STOMP: reconexión y fin de sesión según el código de cierre. |
| `admin`, `simulator`, `layout` | Agentes y Ajustes (OWNER, también los de IA), Simulador (dev) y encabezado (con el estado de la IA). |
| `ai` | Estado de la IA compartido (una consulta cada 15 s para toda la app). |

## Flujo de un mensaje entrante

1. **Meta llama al webhook** `POST /api/webhooks/whatsapp`. Primero se valida la firma
   `X-Hub-Signature-256` sobre los **bytes crudos** del cuerpo; sin firma válida, 403.
2. `WhatsAppPayloadParser` traduce el JSON a mensajes y estados normalizados. Recorre todos los
   `entry`/`changes`/`messages`/`statuses`. Los tipos que aún no se muestran (audio, video,
   ubicación…) se guardan con un texto descriptivo, p. ej. `[Audio recibido]`.
3. `WebhookService` resuelve la empresa de cada evento y llama a `InboundMessageProcessor`:
   - **Idempotencia**: si ya existe un mensaje con ese `{tenantId, externalId}` (Meta reintenta), no
     se repite.
   - Busca o crea el **contacto** y la **conversación abierta** del canal (una por contacto y canal,
     garantizada por un índice único parcial). Si la anterior está resuelta, se abre otra
     ("conversación por episodio").
   - Ante dos mensajes simultáneos de un cliente nuevo, quien pierde la carrera recibe
     `DuplicateKeyException`, vuelve a buscar y continúa: no se pierde ninguno.
   - Contadores con operadores atómicos (`$inc` de no leídos, `$max` de última actividad).
4. Se publica `MessageReceivedEvent`. Primero lo atiende la **asignación por turnos** (si el chat es
   nuevo) y después `RealtimePublisher`, que **relee** la conversación de MongoDB y difunde
   `MESSAGE_CREATED` con la conversación completa al tópico de la empresa.
5. La respuesta HTTP a Meta es 200 si todo quedó guardado. Responde 500 **solo** ante errores
   pasajeros (MongoDB caído, timeout) para que Meta reintente. Un error de datos se guarda como
   "[Mensaje no procesable]" o se registra en el log, sin pedir reintento.
6. Cada minuto, `RiskAlertService` marca **en riesgo** los chats que llevan más de `riskAlertMinutes`
   sin respuesta (una actualización masiva por empresa) y avisa solo de los que acaban de entrar en
   riesgo.

## Flujo de un mensaje saliente

1. El agente envía desde la caja de respuesta: `POST /api/conversations/{id}/messages`.
2. `ReplyService` valida **antes de guardar nada**: conversación abierta (si no, 409), canal
   disponible (si no, 501; fuera de `dev` nunca se usa el simulado) y, en WhatsApp, ventana de 24 h
   abierta (si no, 422).
3. Guarda el mensaje OUTBOUND como **PENDING** antes de llamar al canal.
4. Llama al adaptador del canal. Hay tres resultados posibles:
   - **SENT**, con el id externo (wamid).
   - **FAILED** con su motivo, si el canal rechazó el envío o la conexión ni siquiera se abrió.
   - **FAILED con `CHANNEL_SEND_UNCONFIRMED`**, si hubo timeout o la conexión se cortó después de
     enviar. Puede que el cliente sí lo haya recibido, así que la interfaz pide confirmación antes de
     reintentar.
5. Solo un envío exitoso cuenta como respuesta. Fija `firstResponseAt` y `lastOutboundAt`, pasa la
   conversación de NEW a IN_PROGRESS y limpia la alerta de riesgo, cada cosa con una actualización
   condicional atómica, para que dos agentes respondiendo a la vez no se pisen.
6. Se publica un único evento con el estado final (nunca el PENDING intermedio). Después Meta envía
   los `statuses` (entregado, leído, fallido) por el webhook. `DeliveryStatusUpdater` los aplica solo
   si hacen avanzar el estado y la interfaz los ve por WebSocket.
7. "Reintentar" reenvía el **mismo** mensaje (FAILED → PENDING de forma condicional), sin duplicarlo
   en el chat.
8. **Envíos huérfanos**: si el proceso se cae entre guardar el PENDING y recibir la respuesta del
   canal, el mensaje no se queda en PENDING para siempre. Cada minuto `PendingSendSweeper` pasa a
   FAILED, con el código `CHANNEL_SEND_UNCONFIRMED`, los que llevan más de 5 minutos en PENDING, y
   publica `CONVERSATION_UPDATED` con el mensaje. La antigüedad se mide con `pendingSince` (cuándo
   entró en PENDING, también al reintentar), no con la fecha del mensaje. Si el canal acaba
   confirmando un envío que el barrido ya dio por perdido, el mensaje pasa a SENT.

## Tiempo real

- STOMP sobre WebSocket nativo en `/ws`. El JWT va en el frame CONNECT y se valida con la misma lógica
  que HTTP (usuario activo y del mismo tenant).
- Un único tópico por empresa: `/topic/tenants/{tenantId}/events`. Solo se permite suscribirse al
  propio, y los SEND del cliente se rechazan.
- Hay tres tipos de evento: `MESSAGE_CREATED`, `CONVERSATION_UPDATED` y `ASSIGNED`. Todos llevan la
  conversación completa, para actualizar la bandeja sin otra petición, y se entregan en orden.
- El servidor cierra la sesión al desactivar al agente o cuando vence su JWT.
- El cliente vuelve a pedir la bandeja en **cada** conexión (también al reconectar) y reaplica los
  eventos que lleguen mientras tanto.

## Bandeja paginada ("Cargar más")

- La bandeja trae 25 conversaciones y el botón **Cargar más** pide las siguientes **por cursor**
  (`before` = hora y id de la última cargada), no por número de página. Si un chat sale de la vista
  filtrada entre una página y la siguiente, la página N se correría un puesto y se saltaría otro chat;
  con el cursor eso no pasa. El orden es total y estable: `lastMessageAt` descendente con el id como
  desempate, igual en el backend y en el cliente.
- Con varias páginas cargadas, un evento actualiza o quita una conversación ya cargada esté donde
  esté. Una conversación nueva para la vista solo se inserta si en el orden cae antes de la última
  cargada; si cae después, llega con "Cargar más" (insertarla dejaría un hueco que el cursor saltaría).
- Al reconectar, la recarga trae tantas conversaciones como había cargadas (recorriendo páginas si
  pasan de 100), así que el agente no pierde lo que ya había cargado. Al cambiar los filtros se vuelve
  a la primera página.

## Decisiones principales

- **Procesamiento síncrono del webhook** (sin cola) para no perder mensajes, con idempotencia por id
  externo.
- **Conversación por episodio** con índice único parcial `{tenantId, contactId, channel}` y
  `open: true`. Reabrir con otra abierta responde 409.
- **Nunca leer y guardar** documentos que el webhook actualiza a la vez: los servicios usan
  actualizaciones puntuales y condicionales en MongoDB.
- **Permisos de asignación**: el OWNER asigna a cualquier usuario activo (también a sí mismo). Un
  AGENT solo toma un chat libre o suelta el suyo (403 en otro caso).
- **Errores en español** para el agente: la interfaz traduce por código estable y nunca muestra el
  error técnico.
- **Todo lo de desarrollo vive en el perfil `dev`**: el simulador, el respaldo al canal simulado y los
  datos demo. Una prueba verifica que fuera de `dev` no existe ninguna ruta `/api/dev`.
- **Reloj inyectable** (`Clock`) y tareas programadas desactivables, para probar con tiempo
  controlado sin esperas reales.
- **IA fuera del camino crítico**: la recepción de un mensaje solo encola la tarea de la IA (2 hilos,
  cola acotada); si Ollama está caído o tarda, el mensaje ya está en la bandeja. La IA responde sola solo
  si ningún humano ha escrito en el chat, vuelve a comprobarlo justo antes de enviar, y sus mensajes no
  fijan `firstResponseAt` (la métrica de primera respuesta sigue siendo humana).
- **Respuesta automática por selección, no por redacción**: el modelo solo elige una respuesta rápida
  (lista cerrada de códigos con `enum` en el `format` de Ollama, o `null`) y se envía su texto exacto con
  un saludo fijo. Un modelo de 3b redactando inventaba datos en ~15 % de las preguntas; eligiendo, no
  puede (comprobado con `AiEvaluation`). Sugerencias sí redacta, porque un agente revisa antes de enviar.
- **El texto del cliente es un dato**: va en el mensaje de usuario dentro de un bloque delimitado, una
  línea por mensaje y sin las etiquetas del bloque; las reglas y la información del negocio van en el
  mensaje de sistema. El modelo debe responder un JSON con esquema (`format` de Ollama); cualquier otra
  salida se descarta.

## Limitaciones conocidas

- **IA: un modelo pequeño puede equivocarse.** Con 8 GB de RAM se usa `qwen2.5:3b`. En Sugerencias
  puede proponer datos inventados (los chequeos fijos solo atrapan tallas, colores y temas para una
  persona), por eso el agente revisa antes de enviar. En el automático no inventa, pero a veces elige una
  respuesta rápida que no viene al caso o deja para el equipo algo que sí tenía respuesta.
- **Una sola instancia del backend.** El broker STOMP es el simple en memoria de Spring y el registro
  de sesiones WebSocket también vive en memoria. Con dos o más instancias, los eventos y los cierres
  de sesión no llegarían a los usuarios conectados a otra. Para escalar hace falta un broker externo
  (RabbitMQ o ActiveMQ con relay STOMP, o Redis pub/sub) y propagar los cierres entre nodos.
- **Instagram y Facebook Messenger pendientes.** Existen como canal en el modelo, la bandeja y el
  simulador, pero no tienen adaptador real: fuera de `dev`, responder por ellos da 501 con un mensaje
  claro.
- **Imágenes y otros adjuntos solo descriptivos.** Se guardan como texto (`[Imagen recibida]: …`,
  `[Audio recibido]`); no se descargan ni se muestran, y el envío por WhatsApp es solo texto (sin
  plantillas, así que con la ventana de 24 h cerrada no se puede escribir al cliente).
- **Un número de WhatsApp por instalación**, asignado a la empresa de `WHATSAPP_DEFAULT_TENANT_ID`.
  `TenantResolver` es el punto de extensión para mapear `phone_number_id` → empresa.
