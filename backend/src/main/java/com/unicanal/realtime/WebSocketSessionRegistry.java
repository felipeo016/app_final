package com.unicanal.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Sesiones WebSocket abiertas, con el usuario y el vencimiento del JWT con que se conectó cada una.
 * Permite cerrar las sesiones de un agente desactivado y las de tokens vencidos.
 */
@Component
public class WebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    /** Cierre por política: el cliente no debe reconectar con las mismas credenciales. */
    public static final CloseStatus USER_DEACTIVATED = CloseStatus.POLICY_VIOLATION.withReason("Usuario desactivado");
    /** El JWT del CONNECT venció: el cliente debe volver a iniciar sesión. */
    public static final CloseStatus TOKEN_EXPIRED = CloseStatus.POLICY_VIOLATION
            .withReason("Sesión vencida: inicia sesión de nuevo");

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionOwner> owners = new ConcurrentHashMap<>();
    private final Clock clock;

    public WebSocketSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    private record SessionOwner(String userId, String tenantId, Instant tokenExpiresAt) {
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                owners.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** Asocia la sesión al usuario validado en el CONNECT y al vencimiento de su token. */
    void bind(String sessionId, String userId, String tenantId, Instant tokenExpiresAt) {
        owners.put(sessionId, new SessionOwner(userId, tenantId, tokenExpiresAt));
    }

    /** Cierra todas las sesiones abiertas del usuario. Devuelve cuántas cerró. */
    public int closeSessionsOf(String userId, CloseStatus status) {
        return closeWhere(owner -> owner.userId().equals(userId), status);
    }

    /**
     * Cierra todas las sesiones de una empresa con 1012 (reinicio del servicio): no es un fin de sesión, así que
     * los clientes se reconectan solos y, al reconectar, recargan la bandeja. Lo usa "Restaurar datos demo".
     */
    public int closeSessionsOfTenant(String tenantId, String reason) {
        return closeWhere(owner -> tenantId.equals(owner.tenantId()), CloseStatus.SERVICE_RESTARTED.withReason(reason));
    }

    /**
     * Cierra las sesiones cuyo token ya venció. Corre cada {@code app.websocket.expiry-check-ms} (15 s por
     * defecto), así que una sesión puede durar como mucho ese margen más que su token.
     */
    @Scheduled(fixedDelayString = "${app.websocket.expiry-check-ms:15000}")
    public void closeExpiredSessions() {
        int closed = closeExpired(clock.instant());
        if (closed > 0) {
            log.info("Se cerraron {} sesiones WebSocket con el token vencido", closed);
        }
    }

    int closeExpired(Instant now) {
        return closeWhere(owner -> !now.isBefore(owner.tokenExpiresAt()), TOKEN_EXPIRED);
    }

    private int closeWhere(Predicate<SessionOwner> condition, CloseStatus status) {
        List<String> ids = owners.entrySet().stream().filter(entry -> condition.test(entry.getValue()))
                .map(Map.Entry::getKey).toList();
        int closed = 0;
        for (String id : ids) {
            WebSocketSession session = sessions.get(id);
            owners.remove(id);
            if (session == null) {
                continue;
            }
            try {
                session.close(status);
                closed++;
            } catch (IOException e) {
                log.warn("No se pudo cerrar la sesión WebSocket {}", id, e);
            }
        }
        return closed;
    }
}
