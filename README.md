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

> Estado: Fase 1 (MVP), **Paso 3 – modelo de datos**. Contexto y reglas de arquitectura en
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

Para el perfil de desarrollo (simulador y datos de prueba, disponibles en pasos posteriores):
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

### 4. Frontend (http://localhost:5173)

```bash
cd frontend
npm install
npm run dev
```

Vite redirige `/api` y `/ws` al backend (`VITE_BACKEND_URL`, por defecto `http://localhost:8090`).

## Pruebas y compilación

```bash
# Backend (las pruebas de integración usan Testcontainers: requieren Docker en ejecución)
cd backend && ./mvnw verify

# Frontend
cd frontend && npm run lint && npm run build
```

## Variables de entorno

Ver [`.env.example`](.env.example) para la lista completa y su descripción.
