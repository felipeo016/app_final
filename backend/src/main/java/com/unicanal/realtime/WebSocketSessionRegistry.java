package com.unicanal.realtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Sesiones WebSocket abiertas y el usuario de cada una. Permite cerrar las sesiones de un usuario, p. ej.
 * cuando se desactiva un agente que está conectado.
 */
@Component
public class WebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    /** Cierre por política: el cliente no debe reconectar con las mismas credenciales. */
    public static final CloseStatus USER_DEACTIVATED = CloseStatus.POLICY_VIOLATION.withReason("Usuario desactivado");

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> userBySession = new ConcurrentHashMap<>();

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
                userBySession.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** Asocia la sesión al usuario validado en el CONNECT. */
    void bind(String sessionId, String userId) {
        userBySession.put(sessionId, userId);
    }

    /** Cierra todas las sesiones abiertas del usuario. Devuelve cuántas cerró. */
    public int closeSessionsOf(String userId, CloseStatus status) {
        List<String> ids = userBySession.entrySet().stream().filter(entry -> entry.getValue().equals(userId))
                .map(Map.Entry::getKey).toList();
        int closed = 0;
        for (String id : ids) {
            WebSocketSession session = sessions.get(id);
            userBySession.remove(id);
            if (session == null) {
                continue;
            }
            try {
                session.close(status);
                closed++;
            } catch (IOException e) {
                log.warn("No se pudo cerrar la sesión WebSocket {} del usuario {}", id, userId, e);
            }
        }
        return closed;
    }

    int openSessionsOf(String userId) {
        return (int) userBySession.values().stream().filter(userId::equals).count();
    }
}
