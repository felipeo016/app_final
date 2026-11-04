package com.unicanal.channel;

import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageReceivedEvent;
import com.unicanal.message.MessageRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Guarda un mensaje entrante: busca o crea el contacto y la conversación abierta, inserta el
 * mensaje y actualiza la conversación. Es seguro ante concurrencia y reintentos:
 * <ul>
 *   <li>Mensaje repetido (el canal reintenta): se detecta por {tenantId, externalId} y no se duplica.</li>
 *   <li>Dos mensajes de un contacto nuevo a la vez: quien pierde la carrera recibe DuplicateKeyException
 *       al crear el contacto o la conversación, vuelve a buscar el existente y continúa.</li>
 *   <li>Los contadores de la conversación se actualizan con operadores atómicos ($inc, $max), nunca
 *       leyendo y reescribiendo, para no perder incrementos.</li>
 * </ul>
 */
@Service
public class InboundMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageProcessor.class);
    private static final int MAX_CONVERSATION_ATTEMPTS = 5;
    private static final int PREVIEW_MAX_LENGTH = 120;

    private final ContactRepository contacts;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;

    public InboundMessageProcessor(ContactRepository contacts, ConversationRepository conversations,
            MessageRepository messages, MongoTemplate mongoTemplate, ApplicationEventPublisher events) {
        this.contacts = contacts;
        this.conversations = conversations;
        this.messages = messages;
        this.mongoTemplate = mongoTemplate;
        this.events = events;
    }

    public InboundResult process(String tenantId, InboundMessage inbound) {
        String externalId = inbound.externalMessageId();
        if (externalId != null) {
            Optional<Message> already = messages.findByTenantIdAndExternalId(tenantId, externalId);
            if (already.isPresent()) {
                return InboundResult.duplicate(already.get().getConversationId(), already.get().getId());
            }
        }

        Contact contact = findOrCreateContact(tenantId, inbound);
        OpenConversation open = findOrCreateOpenConversation(tenantId, contact.getId(), inbound.channel());
        Conversation conversation = open.conversation();

        Instant timestamp = inbound.timestamp() != null ? inbound.timestamp() : Instant.now();
        String content = inbound.content() != null ? inbound.content() : "";
        Message message;
        try {
            message = messages.save(new Message(tenantId, conversation.getId(), Direction.INBOUND, inbound.type(),
                    content, externalId, null, timestamp, null));
        } catch (DuplicateKeyException e) {
            // Otro hilo guardó este mismo mensaje entre la comprobación y el insert.
            Message stored = messages.findByTenantIdAndExternalId(tenantId, externalId).orElseThrow(() -> e);
            return InboundResult.duplicate(stored.getConversationId(), stored.getId());
        }

        touchConversation(tenantId, conversation.getId(), timestamp, content);
        events.publishEvent(new MessageReceivedEvent(tenantId, contact.getId(), conversation.getId(),
                message.getId(), open.created()));
        return new InboundResult(false, contact.getId(), conversation.getId(), message.getId(), open.created());
    }

    private Contact findOrCreateContact(String tenantId, InboundMessage inbound) {
        String key = Contact.identityKey(inbound.channel(), inbound.contactExternalId());
        Optional<Contact> existing = contacts.findByTenantIdAndIdentityKey(tenantId, key);
        if (existing.isPresent()) {
            adoptProfileName(tenantId, existing.get(), inbound);
            return existing.get();
        }
        String name = hasText(inbound.contactName()) ? inbound.contactName().trim() : inbound.contactExternalId().trim();
        try {
            return contacts.save(new Contact(tenantId, name, inbound.channel(), inbound.contactExternalId()));
        } catch (DuplicateKeyException e) {
            log.debug("Carrera al crear contacto en tenant {}: se reutiliza el existente", tenantId);
            return contacts.findByTenantIdAndIdentityKey(tenantId, key).orElseThrow(() -> e);
        }
    }

    /** Si el contacto aún se llama como su teléfono/usuario y ahora llega el nombre del perfil, lo adopta. */
    private void adoptProfileName(String tenantId, Contact contact, InboundMessage inbound) {
        String externalId = inbound.contactExternalId().trim();
        if (!hasText(inbound.contactName()) || !externalId.equals(contact.getName())) {
            return;
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(contact.getId()).and("tenantId").is(tenantId)
                        .and("name").is(externalId)),
                Update.update("name", inbound.contactName().trim()), Contact.class);
    }

    private OpenConversation findOrCreateOpenConversation(String tenantId, String contactId, ChannelType channel) {
        for (int attempt = 1; attempt <= MAX_CONVERSATION_ATTEMPTS; attempt++) {
            Optional<Conversation> open = conversations
                    .findByTenantIdAndContactIdAndChannelAndOpenTrue(tenantId, contactId, channel);
            if (open.isPresent()) {
                return new OpenConversation(open.get(), false);
            }
            try {
                return new OpenConversation(conversations.save(new Conversation(tenantId, contactId, channel)), true);
            } catch (DuplicateKeyException e) {
                // Otro hilo creó la conversación abierta a la vez: se vuelve a buscar. También cubre que
                // la conversación se resolviera entre la búsqueda y el insert (entonces se crea otra).
                log.debug("Carrera al crear conversación en tenant {} (intento {})", tenantId, attempt);
            }
        }
        throw new IllegalStateException("No se pudo obtener una conversación abierta tras "
                + MAX_CONVERSATION_ATTEMPTS + " intentos");
    }

    private void touchConversation(String tenantId, String conversationId, Instant timestamp, String content) {
        Query byId = Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId));
        mongoTemplate.updateFirst(byId, new Update()
                .inc("unreadCount", 1)
                .max("lastMessageAt", timestamp)
                .max("lastInboundAt", timestamp), Conversation.class);
        // La vista previa solo cambia si este es el mensaje más reciente (los canales pueden entregar fuera de orden).
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId)
                        .and("lastMessageAt").is(timestamp)),
                Update.update("lastMessagePreview", preview(content)), Conversation.class);
    }

    private static String preview(String content) {
        return content.length() <= PREVIEW_MAX_LENGTH ? content : content.substring(0, PREVIEW_MAX_LENGTH - 1) + "…";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record OpenConversation(Conversation conversation, boolean created) {
    }
}
