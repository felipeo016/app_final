# Unicanal

**Cero mensajes perdidos.** Plataforma omnicanal SaaS para microempresas (2 a 10 empleados): unifica
en una sola bandeja los chats de WhatsApp, Instagram y Facebook Messenger para que varios agentes
atiendan al mismo tiempo, se asignen chats, cambien estados y consulten el historial de cada cliente.

| Carpeta | Contenido |
|---|---|
| `backend/` | Spring Boot 4.1, Java 21, Maven Wrapper (monolito modular), MongoDB, STOMP/WebSocket |
| `frontend/` | React 19 + Vite + TypeScript, React Router 7, `@stomp/stompjs` |
| `docker-compose.yml` | MongoDB 7 local |
| `docs/arquitectura.md` | Módulos, flujos de mensajes, decisiones y limitaciones |
| `.github/copilot-instructions.md` | Reglas del proyecto y **decisiones aprobadas** (fuente de verdad) |

> Estado: Fase 1 (MVP) completa (pasos 1 a 9).

---

## Inicio rápido en Windows (PowerShell)

Todos los comandos se ejecutan en **PowerShell**, desde la carpeta raíz del proyecto salvo que se
indique otra cosa.

### 1. Requisitos

| Herramienta | Versión | Comprobar |
|---|---|---|
| JDK (Temurin recomendado) | 21 | `java -version` |
| Node.js y npm | 20 o superior | `node --version` |
| Docker Desktop | iniciado antes de empezar | `docker info` |
| Git | cualquiera | `git --version` |

No hace falta instalar Maven: el proyecto trae el Maven Wrapper (`mvnw.cmd`). Si `java -version` no
responde, agrega el JDK al `PATH` o define `JAVA_HOME`.

### 2. Variables de entorno (`.env`)

Solo la primera vez. Crea el `.env` (ignorado por git) con un `JWT_SECRET` aleatorio:

```powershell
Copy-Item .env.example .env
$secreto = -join ((1..48) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })
(Get-Content .env) -replace '^JWT_SECRET=.*', "JWT_SECRET=$secreto" | Set-Content .env
```

El backend lee las variables del entorno y, en desarrollo, también del `.env` de la raíz. Sin
`JWT_SECRET` (mínimo 32 caracteres) no arranca. Los secretos (JWT, tokens de Meta) van solo en
variables de entorno o en `.env`, nunca en el código. Lista completa: [`.env.example`](.env.example).

> **El perfil `dev` no se activa desde `.env`**: Spring solo reconoce `SPRING_PROFILES_ACTIVE` como
> variable de entorno real, por eso se define en la terminal del backend (paso 4).

### 3. MongoDB (Docker)

```powershell
docker compose up -d
```

Si tu instalación solo trae el binario independiente, usa `docker-compose up -d`. MongoDB queda en
`localhost:27018` (el contenedor usa 27017 por dentro; en el equipo se usa 27018 para no chocar con
otro MongoDB). Para detenerlo: `docker compose down` (con `-v` también borra los datos).

### 4. Backend (http://localhost:8090)

En una terminal nueva:

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = 'dev'
.\mvnw.cmd spring-boot:run
```

Está listo cuando el log dice `Started UnicanalApplication`. Comprobación:

```powershell
Invoke-RestMethod http://localhost:8090/actuator/health
```

El perfil `dev` habilita el **Simulador** y los **datos demo**. Sin él, la aplicación se comporta como
en producción: no existe ninguna ruta `/api/dev` y no se cargan datos de ejemplo.

### 5. Frontend (http://localhost:5173)

En otra terminal:

```powershell
cd frontend
npm install
npm run dev
```

Abre http://localhost:5173. Vite redirige `/api` y `/ws` al backend (`VITE_BACKEND_URL`, por defecto
`http://localhost:8090`).

---

## Datos de demostración

Crean la empresa **"Tienda Demo"** con las conversaciones del prototipo. Requieren el perfil `dev`
**y** `SEED_DEMO=true`. Son idempotentes: si la empresa demo ya existe, no se crea nada, así que
puedes dejar la opción activada.

Pon `SEED_DEMO=true` en el `.env`, o actívalo solo para un arranque:

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SEED_DEMO = 'true'
.\mvnw.cmd spring-boot:run
```

El log confirma `Datos demo creados: empresa "Tienda Demo"`.

**Credenciales demo** (solo para desarrollo local; las tres cuentas usan la misma contraseña):

| Rol | Nombre | Correo | Contraseña |
|---|---|---|---|
| Dueño (OWNER) | Sofía Ramírez | `dueno@tiendademo.test` | `Demo-Unicanal-2026` |
| Agente | Laura Gómez | `laura@tiendademo.test` | `Demo-Unicanal-2026` |
| Agente | Pedro Martínez | `pedro@tiendademo.test` | `Demo-Unicanal-2026` |

Qué incluye:

| Cliente | Canal | Estado | Para ver… |
|---|---|---|---|
| Ana Ríos | WhatsApp | En proceso | respuesta del agente entregada; tiene una **conversación anterior resuelta** en su perfil |
| Tienda Sol | Instagram | En proceso | conversación **marcada como venta** ($ 1.250.000) |
| Carlos P. | Facebook | En proceso | una respuesta que **no se envió**, con el botón **Reintentar** |
| María D. | WhatsApp | Resuelto | conversación cerrada, en **solo lectura** |
| Jorge V. | Instagram | Nuevo | chat **en riesgo**: lleva 40 min sin respuesta |
| Luisa M. | WhatsApp | En proceso | **ventana de 24 h cerrada**: no se puede responder con texto libre |

Además hay 15 **respuestas rápidas** (horarios, festivos, envíos, contra entrega, medios de pago,
tienda física, cada producto, zapatos y accesorios, descuentos y cambios): escribe `/` en la caja de
respuesta para usarlas. Son también lo único que envía la IA en "Respuesta automática".

Los datos demo nunca envían nada a un canal real: los mensajes de los clientes entran por el mismo
procesador que los webhooks y las respuestas se guardan como ya enviadas.

## Probar sin canales reales: página "Simulador" (perfil `dev`)

Con el backend en perfil `dev`, el menú muestra **Simulador**. Desde ahí puedes:

- **Demo en vivo**: con "Iniciar demo en vivo", cada 20 a 40 segundos (al azar) escribe un cliente por
  WhatsApp, Instagram o Facebook, siguiendo un guion de 18 mensajes de una tienda de ropa (tallas,
  precios, envíos, Nequi, cotizaciones, horarios). Corre en el backend: sigue aunque cambies de página
  o cierres la pestaña, hasta que pulses "Detener", restaures los datos o apagues el backend. No
  necesita internet.
- **Restaurar datos demo**: deja "Tienda Demo" como recién creada (detiene la demo en vivo, borra
  chats, mensajes y respuestas rápidas, quita los agentes agregados y reactiva a Laura y Pedro). Pide
  confirmación. La sesión sigue abierta y los demás navegadores conectados se recargan solos. Solo
  funciona estando dentro de "Tienda Demo".
- hacer que **un cliente escriba** por cualquier canal, ahora o "en el pasado": hace 20 minutos (para
  ver la alerta de riesgo) o hace 25 horas (para ver cerrada la ventana de 24 h de WhatsApp);
- hacer que **el canal informe** que una respuesta fue entregada, leída o no se envió;
- hacer **fallar las próximas respuestas** de tu empresa, también como "sin confirmar" (el canal no
  respondió), para probar el botón **Reintentar**.

Todo llega a la bandeja y al chat abierto en tiempo real. Fuera del perfil `dev` la página no aparece
y sus rutas responden 404.

### Lo mismo desde PowerShell

```powershell
$B = "http://localhost:8090"
$r = Invoke-RestMethod -Method Post "$B/api/auth/login" -ContentType "application/json" -Body '{"email":"dueno@tiendademo.test","password":"Demo-Unicanal-2026"}'
$h = @{ Authorization = "Bearer $($r.token)" }
# charset=utf-8: sin él, Windows PowerShell 5.1 envía el cuerpo en Latin-1 y, si lleva tildes o ñ, el backend responde 400
Invoke-RestMethod -Method Post "$B/api/dev/simulate-inbound" -Headers $h -ContentType "application/json; charset=utf-8" -Body '{"channel":"WHATSAPP","contactExternalId":"573001112233","contactName":"Ana Ríos","text":"Hola, ¿tienen tallas M?"}'
# Alerta de riesgo en 1 minuto en vez de 15 (la tarea revisa cada 60 s)
Invoke-RestMethod -Method Put "$B/api/tenant/settings" -Headers $h -ContentType "application/json" -Body '{"riskAlertMinutes":1}'
```

`channel` puede ser `WHATSAPP`, `INSTAGRAM`, `FACEBOOK` o `SIMULATED`. Repetir el mismo
`contactExternalId` añade mensajes a la misma conversación; uno nuevo crea otro chat.

---

## IA local con Ollama (opcional)

La IA corre en el mismo equipo, sin internet, con [Ollama](https://ollama.com) instalado directamente en
Windows (no en Docker: Docker solo tiene unos 3,7 GB de RAM). Si Ollama está cerrado, la app funciona
igual; el encabezado muestra "IA no disponible" y los chats los atiende el equipo.

1. Instala Ollama y descarga el modelo (una sola vez; luego no hace falta internet):

   ```powershell
   winget install Ollama.Ollama
   # Cierra y vuelve a abrir la terminal
   ollama pull qwen2.5:3b
   ```

   Con 8 GB de RAM usa un modelo pequeño: `qwen2.5:3b` (responde mejor) o `qwen2.5:1.5b` (más rápido).
   Para comparar la velocidad, mira la línea `eval rate` de
   `ollama run qwen2.5:3b --verbose "Hola"`. Con 6 tokens/s o más, usa el de 3b.

2. Ollama queda abierto en segundo plano (icono junto al reloj) y escucha en `http://localhost:11434`.
   El backend precarga el modelo al arrancar y lo deja cargado (`keep_alive`), así que la primera
   respuesta no tarda de más.

3. En **Ajustes → Inteligencia artificial** (solo el dueño) elige el modo y escribe la
   **Información del negocio** (horarios, envíos, medios de pago, productos con precios y tallas). En
   **Respuestas rápidas** deja una por cada pregunta frecuente: en el modo automático la IA solo puede
   enviar esas.

| Modo | Qué hace |
|---|---|
| Apagada | sin IA |
| Sugerencias | botón **Sugerir respuesta** en la caja de respuesta: muestra "Pensando…" y rellena la caja con un texto que **redacta** la IA a partir de la información del negocio y las respuestas rápidas; el agente lo revisa y lo envía |
| Respuesta automática | **por selección, no por redacción**: la IA solo elige cuál respuesta rápida responde la pregunta (o ninguna) y se envía su texto **exacto**, con un saludo fijo ("Hola, Valentina."). Solo en chats que **ningún humano ha respondido todavía**, con la etiqueta "Respuesta automática" y tras mostrar "La IA está escribiendo…" de 2 a 4 s. Si ninguna aplica, el chat queda para el equipo |

**Por qué por selección:** un modelo pequeño (3b) que redacta libremente a veces inventa datos (una
talla que no existe, un descuento que no hay). Eligiendo una respuesta rápida, todo lo que recibe el
cliente lo escribió la empresa: en el peor caso elige una respuesta que no viene al caso, pero nunca
inventa. El modelo responde `{"respuestaRapidaId": "R3"}` o `null`; los códigos válidos se le pasan
como lista cerrada (Ollama no le deja responder otra cosa). Si elige ninguna, un código que no existe
o el JSON es inválido, no se envía nada.

Chequeos fijos (sin modelo), que se aplican antes o después de consultarlo:

- **Temas para una persona**: si el último mensaje habla del estado de un pedido, un pago o
  comprobante, los datos para transferir, una cotización o dotación, un reclamo o una devolución, o
  pide hablar con alguien, ni siquiera se consulta al modelo (en los dos modos).
- **Catálogo** (modo Sugerencias): si la respuesta nombra un producto de la información del negocio,
  las tallas y colores que menciona deben estar en la línea de ese producto; y se descartan frases como
  "todas las tallas" o "cualquier color".

**Consejo al escribir la información del negocio:** pon las tallas una por una ("Tallas 28, 30, 32,
34, 36, 38 y 40"), no como rango ("28 a 40"): un modelo pequeño lee mal los rangos. Lo mismo con
colores y ciudades: lista explícita, y di también lo que **no** hay ("No vendemos zapatos ni
accesorios", "Contra entrega solo en Pereira").

Detalles:

- La IA corre en segundo plano: nunca retrasa la llegada de los mensajes a la bandeja.
- Las respuestas automáticas no cuentan como primera respuesta (`firstResponseAt`): solo sacan el chat
  de "Sin responder" y le quitan la alerta de riesgo. En cuanto un agente responde, la IA deja ese chat.
- Solo se envían al modelo los últimos 10 mensajes. El texto del cliente se trata como dato: la IA
  ignora las órdenes que vengan dentro de un mensaje ("ignora tus instrucciones…").
- La Tienda Demo trae información del negocio y 15 respuestas rápidas que cubren las preguntas de la
  demo en vivo, con la IA en modo **Sugerencias**. Para mostrar la respuesta automática, cámbiala en
  Ajustes (y "Restaurar datos demo" la devuelve a Sugerencias).

**Evaluación con Ollama real** (solo en desarrollo; no forma parte de `mvnw verify` porque necesita
Ollama abierto). Envía 35 preguntas (las del guion de la demo en vivo y casos difíciles) a una empresa
con los datos de la Tienda Demo e imprime, por pregunta, qué respuesta rápida eligió la IA o si la dejó
para el equipo. Falla si alguna respuesta enviada no es exactamente una respuesta rápida:

```powershell
cd backend
.\mvnw.cmd test -Dtest=AiEvaluation -Dai.eval=true
```

Resultado con `qwen2.5:3b` (5 nov 2026): 27 bien, 7 quedaron para el equipo, 1 respuesta que no venía
al caso ("¿camisetas de la Selección Colombia?" → la de la camiseta oversize) y **0 con datos
inventados**.

Variables (en `.env` o en la terminal del backend; todas opcionales):

| Variable | Por defecto | Para qué |
|---|---|---|
| `OLLAMA_URL` | `http://localhost:11434` | dónde escucha Ollama |
| `OLLAMA_MODEL` | `qwen2.5:3b` | modelo a usar (debe estar descargado con `ollama pull`) |
| `OLLAMA_TIMEOUT_SECONDS` | `30` | tiempo máximo de una respuesta |
| `OLLAMA_KEEP_ALIVE` | `24h` | cuánto tiempo queda el modelo cargado tras cada uso |
| `OLLAMA_WARM_UP` | `true` | precargar el modelo al arrancar el backend |

## Cómo se usa

- **Bandeja** (columna izquierda): por defecto solo los chats abiertos (Nuevo y En proceso); el filtro
  de estado permite ver "Todas (con resueltas)" o un estado concreto. Las conversaciones resueltas
  siguen en el historial del perfil del cliente. También filtra por canal, asignado y "Sin responder".
  Un chat en riesgo se resalta en rojo con "lleva X min sin respuesta".
  Muestra 25 chats y **Cargar más** trae los siguientes.
- **Conversación** (centro): historial paginado, respuestas al cliente, notas internas (amarillas,
  el cliente no las ve), respuestas rápidas con `/`, estado de entrega de cada mensaje y contador de
  la ventana de 24 h de WhatsApp. Resolver y reabrir están en el encabezado.
- **Perfil del cliente** (derecha): nombre y notas editables, canales, historial de conversaciones,
  estado, asignación (el dueño elige a cualquiera; un agente puede "Tomar" o "Soltar") y venta en
  pesos colombianos.
- **Agentes** y **Ajustes** (solo el dueño): crear y desactivar agentes, minutos de la alerta y la IA.
- **Encabezado**: "IA lista", "IA apagada" o "IA no disponible" (se actualiza cada 15 s).
- En el celular se ve una columna a la vez, con botones para volver a la bandeja.

La sesión (JWT) se guarda en `sessionStorage`: dura lo que la pestaña. Si el token vence, el backend
responde 401 o cierra el WebSocket y la app vuelve al login con un aviso.

## API (resumen)

Todas las rutas, salvo login, registro, webhooks y salud, piden `Authorization: Bearer <token>`. El
`tenantId` y el `userId` salen siempre del token, nunca del body ni de la URL.

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | público | crea la empresa con su dueño / inicia sesión; devuelven JWT |
| GET | `/api/auth/me` | autenticado | usuario y empresa actuales |
| GET / POST | `/api/agents` · PATCH `/api/agents/{id}/deactivate` | OWNER | equipo |
| GET | `/api/users/assignable` | autenticado | usuarios activos a los que se puede asignar un chat |
| GET / PUT | `/api/tenant/settings` | OWNER | minutos de la alerta de riesgo (1 a 1440); el GET incluye los ajustes de IA |
| PUT | `/api/tenant/settings/ai` | OWNER | modo de la IA (`OFF`, `SUGGEST`, `AUTO`) e información del negocio (hasta 4000 caracteres) |
| GET | `/api/ai/status` | autenticado | `READY` ("IA lista"), `OFF` ("IA apagada") o `UNAVAILABLE` ("IA no disponible") |
| POST | `/api/conversations/{id}/ai/suggest` | autenticado | propone una respuesta sin enviarla (`canAnswer`, `text`); 409 si la IA está apagada, 503 si Ollama no responde |
| GET | `/api/conversations?open&status&channel&assignee&unanswered&size&before` | autenticado | bandeja; `open=true` = solo abiertas; `before` = `nextCursor` anterior ("Cargar más") |
| GET | `/api/conversations/{id}` · `/messages?page&size` | autenticado | conversación y mensajes |
| POST | `/api/conversations/{id}/messages` · `/notes` · `/read` | autenticado | responder, nota interna, marcar leído |
| POST | `/api/conversations/{id}/messages/{messageId}/retry` | autenticado | reintentar un envío fallido |
| PUT | `/api/conversations/{id}/status` · `/assignee` | autenticado | estado y asignación (con reglas por rol) |
| PATCH | `/api/conversations/{id}/sale` | autenticado | marcar o quitar venta |
| GET / PATCH | `/api/contacts/{id}` | autenticado | perfil del cliente |
| GET · POST/PUT/DELETE | `/api/quick-replies` | todos · OWNER | respuestas rápidas |
| GET / POST | `/api/webhooks/whatsapp` | público (firma) | webhook de Meta |
| `/api/dev/**` | | solo perfil `dev` | simulador, demo en vivo (`/live-demo`, `/start`, `/stop`) y `/demo/restore` |
| WebSocket | `/ws` → `/topic/tenants/{tenantId}/events` | JWT en el CONNECT | tiempo real |

## WhatsApp Cloud API (opcional)

Define en `.env` `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_ACCESS_TOKEN`,
`WHATSAPP_PHONE_NUMBER_ID` y `WHATSAPP_DEFAULT_TENANT_ID` (el id de la empresa dueña del número). Sin
ellos el webhook rechaza todo (falla cerrado) y, en `dev`, los envíos por WhatsApp usan el canal
simulado. En Meta configura la URL `https://<tu-dominio>/api/webhooks/whatsapp` con el mismo verify
token. La firma `X-Hub-Signature-256` se valida sobre los bytes crudos; el procesamiento es
idempotente y responde 500 solo ante errores pasajeros para que Meta reintente.

## Pruebas y compilación

```powershell
# Backend: unitarias + integración (Testcontainers: Docker Desktop debe estar iniciado)
cd backend
.\mvnw.cmd verify

# Frontend: lint, compilación y pruebas (Vitest)
cd ..\frontend
npm run lint
npm run build
npm test
```

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| El backend no arranca: "Define la variable de entorno JWT_SECRET" | falta el `.env` o el secreto tiene menos de 32 caracteres (paso 2) |
| `Connection refused` a MongoDB / pruebas de integración fallan | Docker Desktop no está iniciado |
| No aparece "Simulador" ni se cargan los datos demo | el backend no corre con `$env:SPRING_PROFILES_ACTIVE='dev'` en **esa** terminal |
| Los datos demo no aparecen aunque `SEED_DEMO=true` | ya existía `dueno@tiendademo.test` (es idempotente); para recrearlos borra la base con `docker compose down -v` |
| Un `Invoke-RestMethod` con tildes responde 400 | falta `; charset=utf-8` en `-ContentType` (Windows PowerShell 5.1) |
| Al abrir otra pestaña pide iniciar sesión | es lo esperado: la sesión vive en `sessionStorage`, una por pestaña |
| El encabezado dice "IA no disponible" | Ollama está cerrado (ábrelo desde el menú Inicio) o falta el modelo: `ollama pull qwen2.5:3b` |
| La IA no responde una pregunta | el dato no está en la Información del negocio (Ajustes): es lo esperado, el chat queda para el equipo |
| El puerto 8090 o 5173 está ocupado | cambia `SERVER_PORT` en `.env` (y `VITE_BACKEND_URL`) o cierra el otro proceso |

Más detalle técnico en [`docs/arquitectura.md`](docs/arquitectura.md).
