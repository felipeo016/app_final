package com.unicanal.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.LoginRequest;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.whatsapp.WindowPolicy;
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
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Perfil dev + SEED_DEMO=true: el seeder corre al arrancar y crea la empresa demo del prototipo. */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.seed-demo=true")
@Import(TestcontainersConfig.class)
class DevDataSeederIntegrationTest {

    @Autowired
    private DevDataSeeder seeder;
    @Autowired
    private UserRepository users;
    @Autowired
    private AuthService authService;
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

    private String tenantId;

    @BeforeEach
    void loadTenant() {
        tenantId = users.findByEmail(DevDataSeeder.OWNER_EMAIL).orElseThrow().getTenantId();
    }

    private Conversation openOf(ChannelType channel, String externalId) {
        Contact contact = contacts.findByTenantIdAndIdentityKey(tenantId, channel + ":" + externalId).orElseThrow();
        return conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(tenantId, contact.getId()).get(0);
    }

    private List<Message> messagesOf(Conversation conversation) {
        return messages.findByTenantIdAndConversationId(tenantId, conversation.getId(), PageRequest.of(0, 50))
                .getContent();
    }

    @Test
    void createsTheCompanyWithOneOwnerAndTwoAgentsThatCanLogIn() {
        List<User> team = mongoTemplate.find(Query.query(Criteria.where("tenantId").is(tenantId)), User.class);
        assertThat(team).extracting(User::getRole).containsExactlyInAnyOrder(Role.OWNER, Role.AGENT, Role.AGENT);
        assertThat(team).allMatch(User::isActive);
        for (String email : new String[] {DevDataSeeder.OWNER_EMAIL, DevDataSeeder.AGENT_1_EMAIL,
                DevDataSeeder.AGENT_2_EMAIL}) {
            assertThat(authService.login(new LoginRequest(email, DevDataSeeder.DEMO_PASSWORD)).tenant().name())
                    .isEqualTo("Tienda Demo");
        }
    }

    @Test
    void createsTheConversationsOfThePrototype() {
        Conversation ana = openOf(ChannelType.WHATSAPP, "573001234567");
        assertThat(ana.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
        assertThat(messagesOf(ana)).extracting(Message::getContent)
                .contains("¿Tienen tallas M disponibles?")
                .anyMatch(text -> text.startsWith("¡Hola Ana! Sí, tenemos talla M"));

        Conversation sol = openOf(ChannelType.INSTAGRAM, "tiendasol.co");
        assertThat(sol.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
        assertThat(messagesOf(sol)).extracting(Message::getContent).contains("Quiero cotizar 50 unidades");

        Conversation carlos = openOf(ChannelType.FACEBOOK, "carlos.p.fb");
        assertThat(carlos.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
        assertThat(messagesOf(carlos)).extracting(Message::getContent).contains("¿Cuánto cuesta el envío?");

        Conversation maria = openOf(ChannelType.WHATSAPP, "573109876543");
        assertThat(maria.getStatus()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(maria.isOpen()).isFalse();
        assertThat(maria.getLastMessagePreview()).isEqualTo("Gracias, ya recibí el pedido");

        Conversation jorge = openOf(ChannelType.INSTAGRAM, "jorge.v");
        assertThat(jorge.getStatus()).isEqualTo(ConversationStatus.NEW);
        assertThat(jorge.getLastMessagePreview()).isEqualTo("Hola, vi su publicación");
    }

    @Test
    void includesEveryCaseTheDemoShouldShow() {
        // En riesgo
        Conversation jorge = openOf(ChannelType.INSTAGRAM, "jorge.v");
        assertThat(jorge.isAtRisk()).isTrue();
        assertThat(jorge.getAtRiskSince()).isNotNull();
        // Marcada como venta
        Conversation sol = openOf(ChannelType.INSTAGRAM, "tiendasol.co");
        assertThat(sol.isSale()).isTrue();
        assertThat(sol.getSaleAmount()).isEqualByComparingTo(new BigDecimal("1250000"));
        // Un mensaje FAILED con su motivo
        Message failed = messagesOf(openOf(ChannelType.FACEBOOK, "carlos.p.fb")).stream()
                .filter(m -> m.getDeliveryStatus() == DeliveryStatus.FAILED).findFirst().orElseThrow();
        assertThat(failed.getFailureReason()).isEqualTo("No se pudo enviar el mensaje por Facebook");
        assertThat(failed.getFailureCode()).isEqualTo("CHANNEL_SEND_FAILED");
        // Un cliente con una conversación anterior resuelta
        Contact ana = contacts.findByTenantIdAndIdentityKey(tenantId, "WHATSAPP:573001234567").orElseThrow();
        List<Conversation> history = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(tenantId,
                ana.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).isOpen()).isTrue();
        assertThat(history.get(1).getStatus()).isEqualTo(ConversationStatus.RESOLVED);
        // Un WhatsApp con la ventana de 24 h cerrada
        Conversation luisa = openOf(ChannelType.WHATSAPP, "573157778899");
        assertThat(luisa.isOpen()).isTrue();
        assertThat(WindowPolicy.isOpen(luisa.getLastInboundAt(), Instant.now())).isFalse();
        // Respuestas rápidas
        assertThat(quickReplies.findByTenantIdOrderByTitleAsc(tenantId)).extracting(QuickReply::getTitle)
                .containsExactlyInAnyOrderElementsOf(DemoData.QUICK_REPLIES.stream().map(DemoData.DemoQuickReply::title).toList())
                .contains("Envíos", "Horarios", "Medios de pago", "Contra entrega", "Zapatos y accesorios", "Descuentos");
    }

    @Test
    void neverTalksToARealChannel() {
        // Las respuestas se guardan como ya enviadas con ids propios del seeder: ningún adaptador se usó
        List<Message> outbound = mongoTemplate.find(Query.query(Criteria.where("tenantId").is(tenantId)
                .and("direction").is(Direction.OUTBOUND)), Message.class);
        assertThat(outbound).isNotEmpty().allSatisfy(message -> {
            if (message.getDeliveryStatus() == DeliveryStatus.FAILED) {
                assertThat(message.getExternalId()).isNull();
            } else {
                assertThat(message.getExternalId()).startsWith("demo-out-");
            }
        });
    }

    @Test
    void runningItAgainCreatesNothing() {
        long conversationsBefore = mongoTemplate.count(Query.query(Criteria.where("tenantId").is(tenantId)),
                Conversation.class);
        long usersBefore = users.count();

        assertThat(seeder.seed()).isFalse();
        assertThat(seeder.seed()).isFalse();

        assertThat(mongoTemplate.count(Query.query(Criteria.where("tenantId").is(tenantId)), Conversation.class))
                .isEqualTo(conversationsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(mongoTemplate.count(Query.query(Criteria.where("name").is("Tienda Demo")),
                com.unicanal.tenant.Tenant.class)).isEqualTo(1);
    }
}
