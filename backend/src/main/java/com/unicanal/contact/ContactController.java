package com.unicanal.contact;

import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.contact.dto.ContactProfileResponse;
import com.unicanal.contact.dto.ContactProfileResponse.ConversationHistoryItem;
import com.unicanal.contact.dto.UpdateContactRequest;
import com.unicanal.conversation.ConversationRepository;
import jakarta.validation.Valid;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Perfil del cliente (columna derecha de la bandeja) con sus conversaciones anteriores. */
@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactRepository contacts;
    private final ConversationRepository conversations;
    private final MongoTemplate mongoTemplate;

    public ContactController(ContactRepository contacts, ConversationRepository conversations,
            MongoTemplate mongoTemplate) {
        this.contacts = contacts;
        this.conversations = conversations;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/{id}")
    public ContactProfileResponse get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        Contact contact = find(principal.tenantId(), id);
        var history = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(principal.tenantId(), id).stream()
                .map(c -> new ConversationHistoryItem(c.getId(), c.getChannel(), c.getStatus(), c.isOpen(),
                        c.getLastMessagePreview(), c.getLastMessageAt(), c.getCreatedAt()))
                .toList();
        return new ContactProfileResponse(contact.getId(), contact.getName(), contact.getIdentities(),
                contact.getNotes(), contact.getCreatedAt(), history);
    }

    /** Cambia nombre y/o notas con una actualización puntual (no reescribe las identidades). */
    @PatchMapping("/{id}")
    public ContactProfileResponse update(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @Valid @RequestBody UpdateContactRequest request) {
        find(principal.tenantId(), id);
        Update update = new Update();
        if (request.name() != null) {
            update.set("name", request.name().strip());
        }
        if (request.notes() != null) {
            update.set("notes", request.notes().strip());
        }
        if (!update.getUpdateObject().isEmpty()) {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id).and("tenantId")
                    .is(principal.tenantId())), update, Contact.class);
        }
        return get(principal, id);
    }

    private Contact find(String tenantId, String id) {
        return contacts.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.notFound("CONTACT_NOT_FOUND", "Contacto no encontrado"));
    }
}
