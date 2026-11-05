package com.unicanal.conversation;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.whatsapp.WindowPolicy;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.dto.ConversationResponse;
import com.unicanal.conversation.dto.ConversationResponse.ContactRef;
import com.unicanal.conversation.dto.ConversationResponse.UserRef;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * Convierte conversaciones en {@link ConversationResponse}. Busca contactos y agentes en lote (una consulta
 * por colección para toda la página), siempre acotado al tenant.
 */
@Component
public class ConversationViews {

    private final ContactRepository contacts;
    private final UserRepository users;
    private final Clock clock;

    public ConversationViews(ContactRepository contacts, UserRepository users, Clock clock) {
        this.contacts = contacts;
        this.users = users;
        this.clock = clock;
    }

    public ConversationResponse toResponse(String tenantId, Conversation conversation) {
        return toResponses(tenantId, List.of(conversation)).get(0);
    }

    public List<ConversationResponse> toResponses(String tenantId, List<Conversation> conversations) {
        Map<String, Contact> contactsById = byId(contacts.findAllById(ids(conversations, Conversation::getContactId)),
                Contact::getId, Contact::getTenantId, tenantId);
        Map<String, User> usersById = byId(users.findAllById(ids(conversations, Conversation::getAssignedTo)),
                User::getId, User::getTenantId, tenantId);
        Instant now = clock.instant();
        return conversations.stream().map(conversation -> {
            Contact contact = contactsById.get(conversation.getContactId());
            User assignee = conversation.getAssignedTo() == null ? null : usersById.get(conversation.getAssignedTo());
            boolean whatsapp = conversation.getChannel() == ChannelType.WHATSAPP;
            Instant lastInbound = conversation.getLastInboundAt();
            return new ConversationResponse(conversation.getId(),
                    new ContactRef(conversation.getContactId(), contact != null ? contact.getName() : null),
                    conversation.getChannel(), conversation.getStatus(), conversation.isOpen(),
                    conversation.getAssignedTo() == null ? null
                            : new UserRef(conversation.getAssignedTo(), assignee != null ? assignee.getName() : null),
                    conversation.getLastMessagePreview(), conversation.getLastMessageAt(), lastInbound,
                    conversation.getLastOutboundAt(), conversation.getFirstResponseAt(),
                    conversation.getUnreadCount(), conversation.isAtRisk(), conversation.getAtRiskSince(),
                    !whatsapp || WindowPolicy.isOpen(lastInbound, now),
                    whatsapp && lastInbound != null ? lastInbound.plus(WindowPolicy.WINDOW) : null,
                    conversation.isSale(), conversation.getSaleAmount(), conversation.getTags(),
                    conversation.getCreatedAt());
        }).toList();
    }

    private static List<String> ids(Collection<Conversation> conversations, Function<Conversation, String> field) {
        return conversations.stream().map(field).filter(Objects::nonNull).distinct().toList();
    }

    /** Indexa por id descartando cualquier documento de otro tenant (defensa en profundidad). */
    private static <T> Map<String, T> byId(Iterable<T> found, Function<T, String> id, Function<T, String> tenant,
            String tenantId) {
        return StreamSupport.stream(found.spliterator(), false)
                .filter(item -> tenantId.equals(tenant.apply(item)))
                .collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}
