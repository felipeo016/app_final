package com.unicanal.realtime;

import com.unicanal.auth.JwtService.VerifiedToken;
import com.unicanal.auth.PrincipalResolver;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Seguridad de los frames STOMP que llegan del cliente (decisión aprobada 6):
 * <ul>
 *   <li>CONNECT: exige {@code Authorization: Bearer <jwt>} y valida con {@link PrincipalResolver}, la misma
 *       lógica que {@code JwtAuthFilter}: token válido, usuario activo y del mismo tenant del token.</li>
 *   <li>SUBSCRIBE: solo al tópico del propio tenant, y solo si el usuario sigue activo.</li>
 *   <li>SEND: siempre rechazado. Los clientes solo escuchan; si pudieran publicar en /topic podrían
 *       inyectar eventos falsos a sus compañeros.</li>
 * </ul>
 * Cualquier rechazo produce un frame ERROR con el motivo.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final PrincipalResolver principalResolver;
    private final UserRepository users;
    private final WebSocketSessionRegistry sessions;

    public StompAuthInterceptor(PrincipalResolver principalResolver, UserRepository users,
            WebSocketSessionRegistry sessions) {
        this.principalResolver = principalResolver;
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> throw reject("Los clientes no pueden publicar mensajes");
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK...: no requieren más controles
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        String token = header != null && header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length())
                : null;
        VerifiedToken verified = principalResolver.resolveWithExpiry(token).orElseThrow(() -> {
            log.warn("CONNECT WebSocket rechazado: token ausente, inválido o usuario inactivo");
            return reject("No autorizado: inicia sesión de nuevo");
        });
        AuthPrincipal principal = verified.principal();
        accessor.setUser(new StompPrincipal(principal));
        // La sesión vive como mucho lo que el token: al vencer se cierra (ver WebSocketSessionRegistry)
        sessions.bind(accessor.getSessionId(), principal.userId(), principal.tenantId(), verified.expiresAt());
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        AuthPrincipal principal = principalOf(accessor.getUser());
        if (principal == null) {
            throw reject("No autorizado");
        }
        String expected = RealtimeTopics.forTenant(principal.tenantId());
        if (!expected.equals(accessor.getDestination())) {
            log.warn("Suscripción rechazada: el usuario {} intentó suscribirse a {}", principal.userId(),
                    accessor.getDestination());
            throw reject("Solo puedes suscribirte a los eventos de tu empresa");
        }
        // Defensa ante una desactivación que ocurrió justo después del CONNECT
        boolean stillValid = users.findById(principal.userId()).filter(User::isActive)
                .filter(user -> user.getTenantId().equals(principal.tenantId())).isPresent();
        if (!stillValid) {
            throw reject("No autorizado: el usuario ya no está activo");
        }
    }

    private static AuthPrincipal principalOf(Principal user) {
        return user instanceof StompPrincipal stomp ? stomp.auth() : null;
    }

    private static MessageDeliveryException reject(String reason) {
        return new MessageDeliveryException(reason);
    }
}
