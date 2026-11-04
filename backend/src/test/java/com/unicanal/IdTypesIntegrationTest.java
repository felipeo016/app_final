package com.unicanal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelType;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.conversation.QuickReply;
import com.unicanal.conversation.QuickReplyRepository;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import com.unicanal.user.AgentService;
import com.unicanal.user.User;
import com.unicanal.user.dto.CreateAgentRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Regla de IDs: todos los ids ({@code _id} y referencias como tenantId, userId, contactId,
 * conversationId, assignedTo, sentBy) se guardan como String. Nunca ObjectId, ni mezclados.
 * Recorre todas las colecciones de la base, así que cubre automáticamente las que se agreguen.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class IdTypesIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private AuthService authService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private ContactRepository contacts;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;
    @Autowired
    private QuickReplyRepository quickReplies;

    @Test
    void noCollectionStoresObjectIds() {
        AuthResponse owner = authService.register(new RegisterRequest("Acme", "Ana",
                "ids-" + UUID.randomUUID() + "@test.com", "s3cret-pass"));
        String tenantId = owner.tenant().id();
        User agent = agentService.create(tenantId,
                new CreateAgentRequest("Luis", "agent-" + UUID.randomUUID() + "@test.com", "s3cret-pass"));

        Contact contact = contacts.save(new Contact(tenantId, "Cliente", ChannelType.WHATSAPP, "573001112222"));
        Conversation conversation = new Conversation(tenantId, contact.getId(), ChannelType.WHATSAPP);
        conversation.setAssignedTo(agent.getId());
        conversation = conversations.save(conversation);
        messages.save(new Message(tenantId, conversation.getId(), Direction.OUTBOUND, MessageType.TEXT, "hola",
                "wamid.ids", agent.getId(), Instant.now(), DeliveryStatus.SENT));
        quickReplies.save(new QuickReply(tenantId, "Saludo", "Hola"));

        List<String> offenders = new ArrayList<>();
        for (String collection : mongoTemplate.getCollectionNames()) {
            for (Document document : mongoTemplate.getCollection(collection).find()) {
                collectObjectIds(collection, "", document, offenders);
            }
        }

        assertThat(offenders).as("campos guardados como ObjectId").isEmpty();
        assertStringField("tenants", "_id");
        assertStringField("users", "_id", "tenantId");
        assertStringField("contacts", "_id", "tenantId");
        assertStringField("conversations", "_id", "tenantId", "contactId", "assignedTo");
        assertStringField("messages", "_id", "tenantId", "conversationId", "sentBy");
        assertStringField("quickReplies", "_id", "tenantId");

        // Los documentos de esta prueba sí traen sus referencias opcionales, y como String.
        Message savedMessage = messages.findByTenantIdAndExternalId(tenantId, "wamid.ids").orElseThrow();
        assertThat(rawDocument("conversations", conversation.getId()).get("assignedTo")).isEqualTo(agent.getId());
        assertThat(rawDocument("conversations", conversation.getId()).get("contactId")).isEqualTo(contact.getId());
        assertThat(rawDocument("messages", savedMessage.getId()).get("sentBy")).isEqualTo(agent.getId());
        assertThat(rawDocument("messages", savedMessage.getId()).get("conversationId"))
                .isEqualTo(conversation.getId());
    }

    private Document rawDocument(String collection, String id) {
        return mongoTemplate.getCollection(collection).find(new Document("_id", id)).first();
    }

    /** Verifica el tipo en todos los documentos de la colección que tengan el campo (los opcionales pueden faltar). */
    private void assertStringField(String collection, String... fields) {
        for (Document document : mongoTemplate.getCollection(collection).find()) {
            for (String field : fields) {
                Object value = document.get(field);
                if (value != null) {
                    assertThat(value).as(collection + "." + field).isInstanceOf(String.class);
                }
            }
        }
    }

    private static void collectObjectIds(String collection, String path, Object value, List<String> offenders) {
        if (value instanceof ObjectId) {
            offenders.add(collection + "." + path);
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> collectObjectIds(collection, path.isEmpty() ? key.toString()
                    : path + "." + key, child, offenders));
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectObjectIds(collection, path + "[]", child, offenders);
            }
        }
    }
}
