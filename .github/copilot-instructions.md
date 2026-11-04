# Unicanal: contexto del proyecto

Unicanal ("Cero mensajes perdidos") es una plataforma omnicanal SaaS para microempresas
(2 a 10 empleados) que unifica en una sola bandeja los chats de WhatsApp, Instagram y
Facebook Messenger. Varios empleados (agentes) atienden al mismo tiempo, se asignan chats,
cambian estados y consultan el historial de cada cliente.

## Stack
- Backend: Java 21, Spring Boot 4.1.x, Maven (con Maven Wrapper). MONOLITO MODULAR (no microservicios).
  Usar los starters con nombres de Boot 4 (p. ej. spring-boot-starter-webmvc y sus
  spring-boot-starter-*-test).
- Base de datos: MongoDB (Spring Data MongoDB).
- Tiempo real: WebSockets con STOMP (spring-boot-starter-websocket).
- Seguridad: Spring Security + JWT. Roles: OWNER (dueño) y AGENT (agente).
- Frontend: React 19 + Vite + TypeScript, React Router 7, cliente STOMP (@stomp/stompjs).
- Infraestructura local: docker-compose con MongoDB.

## Estructura del repositorio
/backend   -> Spring Boot, paquetes por módulo: auth, tenant, user, contact, conversation,
              message, channel, realtime, metrics, common
/frontend  -> React
docker-compose.yml, .env.example, README.md

## Reglas de arquitectura
- Multi-tenant: cada documento tiene `tenantId`. El tenantId SIEMPRE se toma del JWT,
  nunca del body ni de la URL. Toda consulta filtra por tenantId.
- Los canales se abstraen con la interfaz `ChannelAdapter` (sendMessage, parseWebhook,
  channelType). Implementaciones: SimulatedChannelAdapter (obligatoria, para desarrollo y
  demos), WhatsAppCloudAdapter, y más adelante InstagramAdapter y MessengerAdapter.
- Webhooks de Meta: GET de verificación (hub.mode, hub.verify_token, hub.challenge) y POST
  de recepción validando la firma X-Hub-Signature-256 con el App Secret. Procesar de forma
  idempotente usando el id externo del mensaje (Meta reintenta los webhooks).
- WhatsApp solo permite mensajes libres dentro de una ventana de 24 h desde el último
  mensaje del cliente. Guardar `lastInboundAt` y bloquear el envío libre fuera de la ventana.
- Secretos (tokens de Meta, JWT secret) solo en variables de entorno. Nunca en el código.
  Mantener .env.example actualizado.

## Convenciones
- Código, nombres de clases, variables y endpoints en inglés. Textos de la interfaz en español.
- DTOs separados de los documentos de Mongo. Validación con jakarta.validation.
- Manejo de errores centralizado con @RestControllerAdvice.
- Pruebas: JUnit 5 + Mockito para servicios; pruebas del webhook (firma válida/inválida).
- Trabajar en pasos pequeños: después de cada paso, compilar y correr pruebas.

## Diseño visual
- Layout de 3 columnas: bandeja unificada | conversación | perfil del cliente.
- Colores: encabezado #1F3A4D, acento teal #00807F, fondo de paneles #EAEEF1.
- Etiquetas de canal: WhatsApp #25D366, Instagram #C13584, Facebook #1877F2.
- Etiquetas de estado: Nuevo (naranja), En proceso (azul), Resuelto (verde).

## Decisiones aprobadas
Cambios acordados sobre el plan inicial de la Fase 1 (MVP). Tienen prioridad sobre cualquier
descripción más general de este documento.

1. **Conversación por episodio.** Si un cliente escribe y su última conversación en ese canal
   está RESOLVED, se crea una conversación nueva. `conversations` lleva el campo booleano
   `open` y un índice único parcial `{tenantId, contactId, channel}` con
   `partialFilterExpression {open: true}`. Al pasar a RESOLVED, `open = false`. El perfil del
   contacto lista sus conversaciones anteriores.
2. **Identidad de contactos.** `contacts` usa un campo plano `identityKeys` (formato
   `"CANAL:externalId"`, p. ej. `"WHATSAPP:573001234567"`) con índice único
   `{tenantId, identityKeys}`. No se usa índice multikey sobre `identities`.
3. **Webhook de WhatsApp: statuses.** Además de los mensajes entrantes, se procesan los
   `statuses` (sent, delivered, read, failed): se actualiza `deliveryStatus` del mensaje
   buscándolo por `{tenantId, externalId}` y se publica `CONVERSATION_UPDATED` para que la UI
   muestre el estado. El webhook responde 200 rápido a Meta.
4. **Reglas de estado y asignación.**
   - La primera respuesta del agente en una conversación NEW la pasa a IN_PROGRESS.
   - Al desactivar un agente, sus conversaciones abiertas quedan con `assignedTo = null` y se
     publica el evento `ASSIGNED`.

### Supuestos aceptados
- Paquete base `com.unicanal`; backend en el puerto 8090 y frontend en el 5173 con proxy a
  `/api` y `/ws`.
- El email de `users` es único de forma global, porque el login solo recibe email y contraseña.
- WhatsApp: las credenciales de Meta (token, phone number id, App Secret, verify token) salen de
  variables de entorno, con un solo número por instalación. El tenant del webhook sale de
  `WHATSAPP_DEFAULT_TENANT_ID`, con punto de extensión para mapear `phone_number_id` → tenant.
  Es la única excepción a "tenantId del JWT", porque un webhook no trae JWT.
- Las imágenes (tipo IMAGE) se modelan y se muestran, pero el envío por WhatsApp en el MVP es
  solo texto.
- Pruebas: Mockito para servicios y MockMvc para el webhook; el aislamiento entre tenants se
  prueba con Testcontainers (MongoDB, requiere Docker).
- Frontend con CSS plano y variables, `fetch` como cliente HTTP y Vitest solo para lo básico.
