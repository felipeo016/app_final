package com.unicanal.conversation;

import com.unicanal.channel.ChannelAdapter;
import com.unicanal.channel.ChannelRegistry;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.channel.whatsapp.WindowPolicy;
import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactIdentity;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.dto.MessageResponse;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageSentEvent;
import com.unicanal.message.MessageType;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Respuestas de los agentes y notas internas.
 *
 * <p>Orden del envío, para que ninguna respuesta se pierda sin dejar rastro:
 * <ol>
 *   <li>Validaciones que no dependen del canal remoto (conversación abierta, canal disponible, ventana de
 *       24 h de WhatsApp). Si fallan, no se guarda nada y se responde el error.</li>
 *   <li>Se guarda el mensaje OUTBOUND como PENDING <b>antes</b> de llamar al canal.</li>
 *   <li>Se llama al canal. Si responde, se guarda su id externo y SENT; si falla, FAILED con el motivo,
 *       que la interfaz muestra al agente. La respuesta HTTP es 201 en ambos casos, con el estado final.</li>
 * </ol>
 *
 * <p>La primera respuesta enviada fija {@code firstResponseAt} y pasa la conversación de NEW a IN_PROGRESS,
 * cada cosa con una única actualización condicional atómica: si dos agentes responden a la vez, gana la
 * respuesta más temprana y ninguno pisa al otro.
 */
@Service
public class ReplyService {

    private static final Logger log = LoggerFactory.getLogger(ReplyService.class);
    private static final int PREVIEW_MAX_LENGTH = 120;
    private static final String UNEXPECTED_FAILURE = "Error inesperado del canal al enviar el mensaje";

    private final ConversationService conversationService;
    private final ContactRepository contacts;
    private final MessageRepository messages;
    private final MongoTemplate mongoTemplate;
    private final ChannelRegistry channels;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReplyService(ConversationService conversationService, ContactRepository contacts,
            MessageRepository messages, MongoTemplate mongoTemplate, ChannelRegistry channels,
            ApplicationEventPublisher events, Clock clock) {
        this.conversationService = conversationService;
        this.contacts = contacts;
        this.messages = messages;
        this.mongoTemplate = mongoTemplate;
        this.channels = channels;
        this.events = events;
        this.clock = clock;
    }

    public MessageResponse reply(AuthPrincipal principal, String conversationId, String text) {
        String tenantId = principal.tenantId();
        Conversation conversation = conversationService.find(tenantId, conversationId);
        if (!conversation.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_RESOLVED",
                    "La conversación está resuelta: reábrela para responder");
        }
        String recipient = recipientOf(tenantId, conversation);
        // Lanza 501 si el canal no está disponible (fuera de dev nunca cae al simulado): no se guarda nada.
        ChannelAdapter adapter = channels.adapterFor(conversation.getChannel());
        Instant now = clock.instant();
        if (conversation.getChannel() == ChannelType.WHATSAPP
                && !WindowPolicy.isOpen(conversation.getLastInboundAt(), now)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WHATSAPP_WINDOW_CLOSED",
                    "Pasaron más de 24 horas desde el último mensaje del cliente: WhatsApp ya no permite "
                            + "mensajes libres, solo plantillas aprobadas");
        }

        String body = text.strip();
        Message pending = messages.save(new Message(tenantId, conversationId, Direction.OUTBOUND, MessageType.TEXT,
                body, null, principal.userId(), now, DeliveryStatus.PENDING));
        touchConversation(tenantId, conversationId, now, body);

        Message result;
        try {
            SendResult sent = adapter.sendMessage(new OutboundMessage(tenantId, conversationId, recipient, body,
                    conversation.getLastInboundAt()));
            result = markSent(pending, sent);
            recordResponse(tenantId, conversationId, now);
        } catch (RuntimeException e) {
            String reason = e instanceof ApiException ? e.getMessage() : UNEXPECTED_FAILURE;
            log.warn("Falló el envío del mensaje {} por {} (conversación {}): {}", pending.getId(),
                    conversation.getChannel(), conversationId, reason, e instanceof ApiException ? null : e);
            result = markFailed(pending, reason);
        }
        // Una sola notificación, después de TODAS las escrituras: lleva el mensaje y la conversación final.
        events.publishEvent(new MessageSentEvent(tenantId, conversationId, result.getId(), result.getDeliveryStatus()));
        return MessageResponse.from(result);
    }

    /** Nota interna: solo la ven los agentes, no se envía al cliente ni cuenta como respuesta. */
    public MessageResponse addNote(AuthPrincipal principal, String conversationId, String text) {
        String tenantId = principal.tenantId();
        conversationService.find(tenantId, conversationId);
        Message note = messages.save(new Message(tenantId, conversationId, Direction.OUTBOUND,
                MessageType.INTERNAL_NOTE, text.strip(), null, principal.userId(), clock.instant(), null));
        events.publishEvent(new MessageSentEvent(tenantId, conversationId, note.getId(), null));
        return MessageResponse.from(note);
    }

    private String recipientOf(String tenantId, Conversation conversation) {
        Contact contact = contacts.findByIdAndTenantId(conversation.getContactId(), tenantId)
                .orElseThrow(() -> ApiException.notFound("CONTACT_NOT_FOUND", "Contacto no encontrado"));
        return contact.getIdentities().stream()
                .filter(identity -> identity.channel() == conversation.getChannel())
                .map(ContactIdentity::externalId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONTACT_WITHOUT_IDENTITY",
                        "El cliente no tiene un identificador en " + conversation.getChannel()));
    }

    /** Vista previa y última actividad: el mensaje ya forma parte del chat aunque luego falle el envío. */
    private void touchConversation(String tenantId, String conversationId, Instant timestamp, String content) {
        mongoTemplate.updateFirst(ConversationService.byId(tenantId, conversationId),
                new Update().max("lastMessageAt", timestamp), Conversation.class);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId)
                        .and("lastMessageAt").is(timestamp)),
                Update.update("lastMessagePreview", preview(content)), Conversation.class);
    }

    /**
     * Respuesta efectivamente enviada. Cada actualización es una sola operación condicional:
     * <ul>
     *   <li>{@code firstResponseAt}: solo si no existe o es posterior a esta respuesta (gana la más temprana).</li>
     *   <li>NEW → IN_PROGRESS: solo si sigue en NEW (no pisa un RESOLVED ni un cambio manual).</li>
     *   <li>{@code lastOutboundAt}: con $max, para no retroceder.</li>
     * </ul>
     */
    private void recordResponse(String tenantId, String conversationId, Instant timestamp) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId)
                        .orOperator(Criteria.where("firstResponseAt").is(null),
                                Criteria.where("firstResponseAt").gt(timestamp))),
                Update.update("firstResponseAt", timestamp), Conversation.class);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId)
                        .and("status").is(ConversationStatus.NEW)),
                Update.update("status", ConversationStatus.IN_PROGRESS), Conversation.class);
        mongoTemplate.updateFirst(ConversationService.byId(tenantId, conversationId),
                new Update().max("lastOutboundAt", timestamp), Conversation.class);
    }

    private Message markSent(Message pending, SendResult sent) {
        DeliveryStatus status = sent.deliveryStatus() != null ? sent.deliveryStatus() : DeliveryStatus.SENT;
        Message updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(pending.getId()).and("tenantId").is(pending.getTenantId())
                        .and("deliveryStatus").is(DeliveryStatus.PENDING)),
                Update.update("externalId", sent.externalId()).set("deliveryStatus", status),
                FindAndModifyOptions.options().returnNew(true), Message.class);
        if (updated != null) {
            return updated;
        }
        // El estado ya avanzó por otra vía: se conserva y solo se guarda el id externo.
        return mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(pending.getId())),
                Update.update("externalId", sent.externalId()), FindAndModifyOptions.options().returnNew(true),
                Message.class);
    }

    private Message markFailed(Message pending, String reason) {
        Message updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(pending.getId()).and("tenantId").is(pending.getTenantId())
                        .and("deliveryStatus").is(DeliveryStatus.PENDING)),
                Update.update("deliveryStatus", DeliveryStatus.FAILED).set("failureReason", reason),
                FindAndModifyOptions.options().returnNew(true), Message.class);
        return updated != null ? updated : messages.findById(pending.getId()).orElse(pending);
    }

    private static String preview(String content) {
        return content.length() <= PREVIEW_MAX_LENGTH ? content : content.substring(0, PREVIEW_MAX_LENGTH - 1) + "…";
    }
}
