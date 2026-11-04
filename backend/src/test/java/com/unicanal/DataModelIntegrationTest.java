package com.unicanal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unicanal.channel.ChannelType;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.conversation.QuickReply;
import com.unicanal.conversation.QuickReplyRepository;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** Modelo de datos (Paso 3) contra MongoDB real: restricciones, índices y aislamiento por tenant. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DataModelIntegrationTest {

    @Autowired
    private ContactRepository contacts;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;
    @Autowired
    private QuickReplyRepository quickReplies;
    @Autowired
    private MongoTemplate mongoTemplate;

    private final String tenantA = "tenant-a-" + UUID.randomUUID();
    private final String tenantB = "tenant-b-" + UUID.randomUUID();

    // --- contacts ---

    @Test
    void sameIdentityTwiceInOneTenantIsRejected() {
        contacts.save(new Contact(tenantA, "Ana", ChannelType.WHATSAPP, "573001112222"));

        assertThatThrownBy(() -> contacts.save(new Contact(tenantA, "Ana bis", ChannelType.WHATSAPP, "573001112222")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameIdentityInAnotherTenantIsAllowed() {
        contacts.save(new Contact(tenantA, "Ana", ChannelType.WHATSAPP, "573001112222"));

        Contact other = contacts.save(new Contact(tenantB, "Ana", ChannelType.WHATSAPP, "573001112222"));

        assertThat(other.getId()).isNotNull();
    }

    @Test
    void secondContactCannotClaimAnIdentityOfAnotherContactInTheTenant() {
        Contact ana = contacts.save(new Contact(tenantA, "Ana", ChannelType.WHATSAPP, "573001112222"));
        Contact luis = contacts.save(new Contact(tenantA, "Luis", ChannelType.INSTAGRAM, "luis_ig"));

        ana.addIdentity(ChannelType.INSTAGRAM, "luis_ig");

        assertThatThrownBy(() -> contacts.save(ana)).isInstanceOf(DuplicateKeyException.class);
        assertThat(luis.getIdentityKeys()).containsExactly("INSTAGRAM:luis_ig");
    }

    @Test
    void contactIsFoundByAnyOfItsIdentitiesOnlyWithinItsTenant() {
        Contact ana = new Contact(tenantA, "Ana", ChannelType.WHATSAPP, "573001112222");
        ana.addIdentity(ChannelType.INSTAGRAM, "ana_ig");
        ana = contacts.save(ana);

        assertThat(contacts.findByTenantIdAndIdentityKey(tenantA, "WHATSAPP:573001112222"))
                .get().extracting(Contact::getId).isEqualTo(ana.getId());
        assertThat(contacts.findByTenantIdAndIdentityKey(tenantA, "INSTAGRAM:ana_ig")).isPresent();
        assertThat(contacts.findByTenantIdAndIdentityKey(tenantB, "WHATSAPP:573001112222")).isEmpty();
        assertThat(contacts.findByIdAndTenantId(ana.getId(), tenantB)).isEmpty();
    }

    // --- conversations ---

    @Test
    void onlyOneOpenConversationPerContactAndChannel() {
        conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP));

        assertThatThrownBy(() -> conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void openConversationsOfDifferentChannelsContactsOrTenantsDoNotConflict() {
        conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP));

        conversations.save(new Conversation(tenantA, "contact-1", ChannelType.INSTAGRAM));
        conversations.save(new Conversation(tenantA, "contact-2", ChannelType.WHATSAPP));
        conversations.save(new Conversation(tenantB, "contact-1", ChannelType.WHATSAPP));
    }

    @Test
    void resolvedConversationDoesNotBlockANewEpisodeAndHistoryIsKept() {
        Conversation first = conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP));
        first.setStatus(ConversationStatus.RESOLVED);
        conversations.save(first);

        Conversation second = conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP));

        assertThat(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(tenantA, "contact-1",
                ChannelType.WHATSAPP)).get().extracting(Conversation::getId).isEqualTo(second.getId());
        assertThat(conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(tenantA, "contact-1"))
                .extracting(Conversation::getId).containsExactly(second.getId(), first.getId());
    }

    @Test
    void manyResolvedConversationsCanCoexist() {
        for (int i = 0; i < 3; i++) {
            Conversation conversation = new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP);
            conversation.setStatus(ConversationStatus.RESOLVED);
            conversations.save(conversation);
        }

        assertThat(conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(tenantA, "contact-1")).hasSize(3);
    }

    @Test
    void reopeningAResolvedConversationWhileAnotherIsOpenConflicts() {
        Conversation old = new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP);
        old.setStatus(ConversationStatus.RESOLVED);
        old = conversations.save(old);
        conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP));

        old.setStatus(ConversationStatus.IN_PROGRESS);

        Conversation reopened = old;
        assertThatThrownBy(() -> conversations.save(reopened)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void conversationsAreScopedByTenant() {
        Conversation conversation = conversations.save(new Conversation(tenantA, "contact-1", ChannelType.WHATSAPP));

        assertThat(conversations.findByIdAndTenantId(conversation.getId(), tenantA)).isPresent();
        assertThat(conversations.findByIdAndTenantId(conversation.getId(), tenantB)).isEmpty();
        assertThat(conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(tenantB, "contact-1")).isEmpty();
    }

    @Test
    void conversationFieldsRoundTripWithSaleAsDecimalAndSpecNames() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Conversation conversation = new Conversation(tenantA, "contact-1", ChannelType.SIMULATED);
        conversation.setAssignedTo("agent-1");
        conversation.setLastMessageAt(now);
        conversation.setLastInboundAt(now);
        conversation.setFirstResponseAt(now);
        conversation.setUnreadCount(3);
        conversation.setTags(List.of("vip", "reclamo"));
        conversation.markSale(true, new BigDecimal("1234.56"));
        conversation = conversations.save(conversation);

        Conversation loaded = conversations.findByIdAndTenantId(conversation.getId(), tenantA).orElseThrow();
        assertThat(loaded.getChannel()).isEqualTo(ChannelType.SIMULATED);
        assertThat(loaded.getStatus()).isEqualTo(ConversationStatus.NEW);
        assertThat(loaded.isOpen()).isTrue();
        assertThat(loaded.getAssignedTo()).isEqualTo("agent-1");
        assertThat(loaded.getLastInboundAt()).isEqualTo(now);
        assertThat(loaded.getUnreadCount()).isEqualTo(3);
        assertThat(loaded.getTags()).containsExactly("vip", "reclamo");
        assertThat(loaded.isSale()).isTrue();
        assertThat(loaded.getSaleAmount()).isEqualByComparingTo("1234.56");

        Document raw = mongoTemplate.getCollection("conversations")
                .find(new Document("_id", conversation.getId())).first();
        assertThat(raw).containsEntry("isSale", true);
        assertThat(raw.get("saleAmount")).isInstanceOf(Decimal128.class);
    }

    // --- messages ---

    @Test
    void sameExternalIdInOneTenantIsRejectedForIdempotency() {
        messages.save(inbound(tenantA, "conv-1", "wamid.AAA", Instant.now()));

        assertThatThrownBy(() -> messages.save(inbound(tenantA, "conv-2", "wamid.AAA", Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameExternalIdInAnotherTenantIsAllowed() {
        messages.save(inbound(tenantA, "conv-1", "wamid.AAA", Instant.now()));

        messages.save(inbound(tenantB, "conv-1", "wamid.AAA", Instant.now()));

        assertThat(messages.findByTenantIdAndExternalId(tenantA, "wamid.AAA")).isPresent();
        assertThat(messages.findByTenantIdAndExternalId(tenantB, "wamid.AAA")).isPresent();
        assertThat(messages.findByTenantIdAndExternalId(tenantB, "wamid.ZZZ")).isEmpty();
    }

    @Test
    void messagesWithoutExternalIdAreUnlimited() {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            messages.save(new Message(tenantA, "conv-1", Direction.OUTBOUND, MessageType.INTERNAL_NOTE, "nota " + i,
                    null, "agent-1", now, null));
        }
        messages.save(new Message(tenantA, "conv-1", Direction.OUTBOUND, MessageType.TEXT, "hola", null, "agent-1",
                now, DeliveryStatus.PENDING));

        assertThat(messages.findByTenantIdAndConversationId(tenantA, "conv-1", PageRequest.of(0, 10)))
                .hasSize(4);
    }

    @Test
    void outboundMessageCanReceiveItsExternalIdAndDeliveryStatusLater() {
        Message sent = messages.save(new Message(tenantA, "conv-1", Direction.OUTBOUND, MessageType.TEXT, "hola",
                null, "agent-1", Instant.now(), DeliveryStatus.PENDING));

        sent.setExternalId("wamid.OUT1");
        sent.setDeliveryStatus(DeliveryStatus.SENT);
        messages.save(sent);
        Message viaStatus = messages.findByTenantIdAndExternalId(tenantA, "wamid.OUT1").orElseThrow();
        viaStatus.setDeliveryStatus(DeliveryStatus.READ);
        messages.save(viaStatus);

        assertThat(messages.findById(sent.getId()).orElseThrow().getDeliveryStatus()).isEqualTo(DeliveryStatus.READ);
    }

    @Test
    void messagesArePagedNewestFirstAndScopedByTenant() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < 5; i++) {
            messages.save(inbound(tenantA, "conv-1", "wamid." + i, base.plusSeconds(i)));
        }
        messages.save(inbound(tenantB, "conv-1", "wamid.other", base.plusSeconds(99)));

        Page<Message> first = messages.findByTenantIdAndConversationId(tenantA, "conv-1",
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "timestamp")));
        Page<Message> last = messages.findByTenantIdAndConversationId(tenantA, "conv-1",
                PageRequest.of(2, 2, Sort.by(Sort.Direction.DESC, "timestamp")));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getContent()).extracting(Message::getExternalId).containsExactly("wamid.4", "wamid.3");
        assertThat(last.getContent()).extracting(Message::getExternalId).containsExactly("wamid.0");
    }

    // --- quick replies ---

    @Test
    void quickRepliesAreListedByTitleAndScopedByTenant() {
        quickReplies.save(new QuickReply(tenantA, "Saludo", "¡Hola! ¿En qué te ayudo?"));
        quickReplies.save(new QuickReply(tenantA, "Horarios", "Atendemos de 9 a 18 h"));
        QuickReply foreign = quickReplies.save(new QuickReply(tenantB, "Ajeno", "texto"));

        assertThat(quickReplies.findByTenantIdOrderByTitleAsc(tenantA)).extracting(QuickReply::getTitle)
                .containsExactly("Horarios", "Saludo");
        assertThat(quickReplies.deleteByIdAndTenantId(foreign.getId(), tenantA)).isZero();
        assertThat(quickReplies.findByIdAndTenantId(foreign.getId(), tenantB)).isPresent();
        assertThat(quickReplies.deleteByIdAndTenantId(foreign.getId(), tenantB)).isEqualTo(1);
    }

    // --- indexes ---

    @Test
    void declaredIndexesExistWithTheirUniquenessAndPartialFilters() {
        Map<String, IndexInfo> contactIndexes = indexes("contacts");
        assertThat(contactIndexes).containsKeys("tenant_identity_unique", "tenant_name");
        assertThat(contactIndexes.get("tenant_identity_unique").isUnique()).isTrue();
        assertThat(contactIndexes.get("tenant_identity_unique").getIndexFields())
                .extracting(field -> field.getKey()).containsExactly("tenantId", "identityKeys");

        Map<String, IndexInfo> conversationIndexes = indexes("conversations");
        assertThat(conversationIndexes).containsKeys("tenant_status_lastMessage", "tenant_assigned_status",
                "tenant_contact_channel_open_unique", "tenant_contact_createdAt", "tenant_atRisk");
        IndexInfo openUnique = conversationIndexes.get("tenant_contact_channel_open_unique");
        assertThat(openUnique.isUnique()).isTrue();
        assertThat(openUnique.getPartialFilterExpression()).contains("open").contains("true");

        Map<String, IndexInfo> messageIndexes = indexes("messages");
        assertThat(messageIndexes).containsKeys("tenant_conversation_timestamp", "tenant_externalId_unique");
        assertThat(messageIndexes.get("tenant_externalId_unique").isUnique()).isTrue();
        assertThat(messageIndexes.get("tenant_externalId_unique").getPartialFilterExpression())
                .contains("externalId");

        assertThat(indexes("quickReplies")).containsKey("tenant_title");
    }

    private Map<String, IndexInfo> indexes(String collection) {
        return mongoTemplate.indexOps(collection).getIndexInfo().stream()
                .collect(Collectors.toMap(IndexInfo::getName, index -> index));
    }

    private static Message inbound(String tenantId, String conversationId, String externalId, Instant timestamp) {
        return new Message(tenantId, conversationId, Direction.INBOUND, MessageType.TEXT, "hola", externalId, null,
                timestamp, null);
    }
}
