# Unicanal

**Cero mensajes perdidos.** Plataforma omnicanal SaaS para microempresas: unifica en una sola
bandeja los chats de WhatsApp, Instagram y Facebook Messenger para que varios agentes atiendan,
se asignen chats y consulten el historial de cada cliente.

Monorepo:

| Carpeta | Contenido |
|---|---|
| `backend/` | Spring Boot 4.1, Java 21, Maven (monolito modular), MongoDB |
| `frontend/` | React 19 + Vite + TypeScript |
| `docker-compose.yml` | MongoDB local |

> Estado: Fase 1 (MVP), **Paso 8b – frontend: conversación y simulador**. Contexto y reglas de arquitectura en
> [`.github/copilot-instructions.md`](.github/copilot-instructions.md).

## Requisitos

- JDK 21 (Temurin) con `java` en el PATH o `JAVA_HOME` configurado
- Node.js 20+ y npm
- Docker (solo para MongoDB)

No necesitas instalar Maven: el proyecto incluye el Maven Wrapper (`mvnw`).

## Cómo correr todo

### 1. Variables de entorno

```bash
cp .env.example .env
```

El backend toma su configuración de **variables de entorno** y, para desarrollo local, también
lee de forma opcional el archivo `.env` de la raíz del repo. Hay que definir **`JWT_SECRET`**
(mínimo 32 caracteres; el backend no arranca sin él): genera uno con `openssl rand -base64 48`.
El resto tiene valores por defecto para desarrollo. Los secretos (JWT, tokens de Meta) solo van
en variables de entorno o en `.env` (ignorado por git), nunca en el código.

### 2. MongoDB

```bash
docker compose up -d        # o, si solo tienes el binario standalone: docker-compose up -d
```

Si usas Docker Desktop, asegúrate de que esté iniciado. MongoDB queda en `localhost:27018`: el
puerto interno del contenedor es 27017, pero en el host se usa 27018 para no chocar con otros
MongoDB locales. Se cambia con `MONGO_PORT` y `MONGODB_URI`.
Para detenerlo: `docker compose down` (añade `-v` para borrar los datos).

### 3. Backend (http://localhost:8090)

```bash
cd backend
./mvnw spring-boot:run          # Windows (cmd/PowerShell): mvnw.cmd spring-boot:run
```

Para el perfil de desarrollo (simulador de clientes; los datos de prueba llegan en pasos posteriores):
`SPRING_PROFILES_ACTIVE=dev`.

Comprobación: `curl http://localhost:8090/actuator/health` debe responder `{"status":"UP",...}`.

#### API disponible (Paso 2)

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| POST | `/api/auth/register` | público | crea la empresa y su usuario OWNER; devuelve JWT |
| POST | `/api/auth/login` | público | devuelve JWT |
| GET | `/api/auth/me` | autenticado | usuario y empresa actuales |
| GET / POST | `/api/agents` | OWNER | listar / crear agentes |
| PATCH | `/api/agents/{id}/deactivate` | OWNER | desactivar un agente |

Las rutas protegidas piden `Authorization: Bearer <token>`. El `tenantId` y el `userId` salen
siempre del token, nunca del body ni de la URL. Un agente desactivado pierde el acceso de
inmediato, aunque su token no haya vencido.

#### Canales (Paso 4)

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| POST | `/api/dev/simulate-inbound` | JWT, **solo perfil `dev`** | simula que un cliente escribe por cualquier canal |
| GET | `/api/webhooks/whatsapp` | público | verificación de Meta: devuelve `hub.challenge` como texto plano |
| POST | `/api/webhooks/whatsapp` | público, firmado | mensajes y estados de WhatsApp Cloud API |

**Simulador (dev).** Crea el contacto y la conversación si no existen, en la empresa del JWT:

```bash
curl -X POST http://localhost:8090/api/dev/simulate-inbound \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"channel":"INSTAGRAM","contactExternalId":"ana_ig","contactName":"Ana","text":"Hola"}'
```

`channel` admite `WHATSAPP`, `INSTAGRAM`, `FACEBOOK` y `SIMULATED`. Un `messageId` fijo simula un
reintento del canal (no se duplica).

**WhatsApp.** Define en el entorno `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET`,
`WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID` y `WHATSAPP_DEFAULT_TENANT_ID` (el id de la empresa
dueña del número). Sin ellos el webhook rechaza todo (falla cerrado). En Meta, configura la URL
`https://<tu-dominio>/api/webhooks/whatsapp` y el mismo verify token. Detalles de comportamiento:

- La firma `X-Hub-Signature-256` se valida sobre los **bytes crudos** del cuerpo, antes de leer el JSON.
- Responde 200 cuando todo se guardó y 5xx si algún evento falló, para que Meta reintente; el
  procesamiento es idempotente por el id del mensaje.
- Un POST puede traer varios `entry`, `changes`, `messages` y `statuses`: se procesan todos.
- Audio, video, sticker, ubicación, reacciones, etc. se guardan como mensaje entrante con un texto
  descriptivo (p. ej. `[Audio recibido]`). La descarga de archivos no está implementada.
- Se ignoran (con aviso en el log) los eventos de un `phone_number_id` distinto al configurado.
- Texto libre solo dentro de las 24 h posteriores al último mensaje del cliente.

### 4. Frontend (http://localhost:5173)

```bash
cd frontend
npm install
npm run dev
```

Vite redirige `/api` y `/ws` al backend (`VITE_BACKEND_URL`, por defecto `http://localhost:8090`).
Abre http://localhost:5173, registra una empresa en **Regístrala** y entrarás a la bandeja.

La sesión (JWT) se guarda en `sessionStorage`: dura lo que la pestaña. Si el token vence, el
backend responde 401 o cierra el WebSocket por sesión vencida, y la app vuelve al login con un aviso.

#### Probar sin canales reales: página "Simulador" (perfil `dev`)

Con el backend arrancado con `SPRING_PROFILES_ACTIVE=dev`, el encabezado muestra **Simulador**
(fuera de dev no aparece y sus rutas `/api/dev/**` responden 404). Desde ahí puedes:

- hacer que un cliente escriba por cualquier canal, ahora o "en el pasado" (hace 20 min para ver la
  alerta de riesgo; hace 25 h para ver cerrada la ventana de 24 h de WhatsApp);
- hacer que el canal informe que una respuesta fue entregada, leída o no se envió;
- hacer fallar las próximas respuestas de tu empresa para probar el botón **Reintentar**.

Todo llega a la bandeja y al chat abierto en tiempo real.

#### Lo mismo desde la terminal

Inicia sesión con el correo y la contraseña con que registraste la empresa y simula que un cliente
escribe. La conversación aparece al instante en la bandeja. Para ver la alerta "cero mensajes
perdidos" sin esperar 15 minutos, el último comando baja el umbral a 1 minuto (la tarea revisa cada 60 s).

**PowerShell (Windows):**

```powershell
$B = "http://localhost:8090"
$r = Invoke-RestMethod -Method Post "$B/api/auth/login" -ContentType "application/json" -Body '{"email":"TU_CORREO","password":"TU_CLAVE"}'
$h = @{ Authorization = "Bearer $($r.token)" }
# charset=utf-8: sin él, Windows PowerShell 5.1 envía el cuerpo en Latin-1 y, si lleva tildes o ñ, el backend responde 400
Invoke-RestMethod -Method Post "$B/api/dev/simulate-inbound" -Headers $h -ContentType "application/json; charset=utf-8" -Body '{"channel":"WHATSAPP","contactExternalId":"573001112233","contactName":"Ana Ríos","text":"Hola, ¿tienen tallas M?"}'
Invoke-RestMethod -Method Put "$B/api/tenant/settings" -Headers $h -ContentType "application/json" -Body '{"riskAlertMinutes":1}'
```

**bash (Linux, macOS, Git Bash):**

```bash
B=http://localhost:8090
TOKEN=$(curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"TU_CORREO","password":"TU_CLAVE"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -X POST $B/api/dev/simulate-inbound -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"channel":"WHATSAPP","contactExternalId":"573001112233","contactName":"Ana Ríos","text":"Hola, ¿tienen tallas M?"}'
curl -X PUT $B/api/tenant/settings -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"riskAlertMinutes":1}'
```

`channel` puede ser `WHATSAPP`, `INSTAGRAM`, `FACEBOOK` o `SIMULATED`. Repetir el mismo
`contactExternalId` añade mensajes a la misma conversación; uno nuevo crea otro chat.

## Pruebas y compilación

```bash
# Backend (las pruebas de integración usan Testcontainers: requieren Docker en ejecución)
cd backend && ./mvnw verify

# Frontend
cd frontend && npm run lint && npm run build && npm test
```

## Variables de entorno

Ver [`.env.example`](.env.example) para la lista completa y su descripción.
