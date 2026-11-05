package com.unicanal.realtime;

import com.unicanal.conversation.ConversationAssignedEvent;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.conversation.ConversationUpdatedEvent;
import com.unicanal.conversation.ConversationViews;
import com.unicanal.conversation.dto.ConversationResponse;
import com.unicanal.conversation.dto.MessageResponse;
import com.unicanal.message.MessageReceivedEvent;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageSentEvent;
import com.unicanal.message.MessageStatusChangedEvent;
import com.unicanal.realtime.RealtimeEvent.Type;
import com.unicanal.user.AgentDeactivatedEvent;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Difunde por WebSocket los eventos de dominio al tópico de la empresa.
 *
 * <p>Orden garantizado: los servicios publican cada evento de Spring solo DESPUÉS de completar su escritura
 * en MongoDB, y este componente vuelve a leer la conversación y el mensaje desde la base antes de difundir.
 * Así el cliente nunca recibe un estado que todavía no se guardó. Los listeners de dominio (p. ej. la
 * asignación por turnos) corren antes que este ({@link Order} más bajo), de modo que el evento de un chat
 * nuevo ya lleva su agente asignado.
 *
 * <p>Un fallo al difundir nunca deshace ni hace fallar la operación: se registra y se sigue (la interfaz
 * se resincroniza al recargar).
 */
@Component
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final SimpMessagingTemplate messaging;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationViews views;
    private final WebSocketSessionRegistry sessions;
    private final Clock clock;

    public RealtimePublisher(SimpMessagingTemplate messaging, ConversationRepository conversations,
            MessageRepository messages, ConversationViews views, WebSocketSessionRegistry sessions, Clock clock) {
        this.messaging = messaging;
        this.conversations = conversations;
        this.messages = messages;
        this.views = views;
        this.sessions = sessions;
        this.clock = clock;
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onMessageReceived(MessageReceivedEvent event) {
        publish(Type.MESSAGE_CREATED, event.tenantId(), event.conversationId(), event.messageId());
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onMessageSent(MessageSentEvent event) {
        publish(Type.MESSAGE_CREATED, event.tenantId(), event.conversationId(), event.messageId());
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onDeliveryStatusChanged(MessageStatusChangedEvent event) {
        publish(Type.CONVERSATION_UPDATED, event.tenantId(), event.conversationId(), event.messageId());
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onConversationUpdated(ConversationUpdatedEvent event) {
        publish(Type.CONVERSATION_UPDATED, event.tenantId(), event.conversationId(), null);
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onAssigned(ConversationAssignedEvent event) {
        publish(Type.ASSIGNED, event.tenantId(), event.conversationId(), null);
    }

    /**
     * Agente desactivado: se cierran sus sesiones WebSocket ANTES que nada (orden más alto), para que no
     * reciba ni siquiera los eventos de reasignación de sus propios chats.
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onAgentDeactivated(AgentDeactivatedEvent event) {
        int closed = sessions.closeSessionsOf(event.agentId(), WebSocketSessionRegistry.USER_DEACTIVATED);
        if (closed > 0) {
            log.info("Se cerraron {} sesiones WebSocket del agente desactivado {}", closed, event.agentId());
        }
    }

    private void publish(Type type, String tenantId, String conversationId, String messageId) {
        try {
            Optional<ConversationResponse> conversation = conversations.findByIdAndTenantId(conversationId, tenantId)
                    .map(found -> views.toResponse(tenantId, found));
            if (conversation.isEmpty()) {
                return;
            }
            MessageResponse message = messageId == null ? null : messages.findById(messageId)
                    .filter(found -> tenantId.equals(found.getTenantId()))
                    .map(MessageResponse::from)
                    .orElse(null);
            messaging.convertAndSend(RealtimeTopics.forTenant(tenantId),
                    new RealtimeEvent(type, conversationId, conversation.get(), message, clock.instant()));
        } catch (RuntimeException e) {
            log.error("No se pudo difundir {} de la conversación {} (tenant {})", type, conversationId, tenantId, e);
        }
    }
}
