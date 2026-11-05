package com.unicanal.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.LoginRequest;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.conversation.QuickReply;
import com.unicanal.message.Message;
import com.unicanal.tenant.Tenant;
import com.unicanal.user.AgentService;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import com.unicanal.user.dto.CreateAgentRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** "Restaurar datos demo": deja la empresa demo como recién creada sin cerrar la sesión de quien la restaura. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.seed-demo=true")
@Import({TestcontainersConfig.class, ManualDemoScheduler.Config.class})
class DemoRestoreIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private UserRepository users;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private LiveDemoService liveDemo;
    @Autowired
    private ManualDemoScheduler scheduler;

    private long count(Class<?> type, String tenantId) {
        return mongoTemplate.count(Query.query(Criteria.where("tenantId").is(tenantId)), type);
    }

    @Test
    void restoringUndoesEverythingDoneDuringTheDemoAndKeepsTheSessionAlive() throws Exception {
        String token = authService.login(new LoginRequest(DemoData.OWNER_EMAIL, DemoData.DEMO_PASSWORD)).token();
        String tenantId = users.findByEmail(DemoData.OWNER_EMAIL).orElseThrow().getTenantId();
        long conversationsSeeded = count(Conversation.class, tenantId);
        long messagesSeeded = count(Message.class, tenantId);

        // Lo que pasa durante una feria: demo en vivo, chats resueltos, un agente nuevo, otro desactivado...
        liveDemo.start(tenantId);
        scheduler.runNext();
        scheduler.runNext();
        mongoTemplate.updateMulti(Query.query(Criteria.where("tenantId").is(tenantId)),
                Update.update("status", ConversationStatus.RESOLVED).set("open", false), Conversation.class);
        agentService.create(tenantId, new CreateAgentRequest("Visitante", "visitante-" + UUID.randomUUID()
                + "@test.com", "s3cret-pass"));
        User laura = users.findByEmail(DemoData.AGENT_1_EMAIL).orElseThrow();
        agentService.deactivate(tenantId, laura.getId());
        mongoTemplate.save(new QuickReply(tenantId, "Extra", "texto"));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(tenantId)),
                Update.update("settings.riskAlertMinutes", 1), Tenant.class);

        mockMvc.perform(post("/api/dev/demo/restore").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(true));

        // La demo en vivo se detuvo
        assertThat(liveDemo.status(tenantId).running()).isFalse();
        // Los datos volvieron a los del guion
        assertThat(count(Conversation.class, tenantId)).isEqualTo(conversationsSeeded);
        assertThat(count(Message.class, tenantId)).isEqualTo(messagesSeeded);
        assertThat(mongoTemplate.count(Query.query(Criteria.where("tenantId").is(tenantId).and("open").is(true)),
                Conversation.class)).isEqualTo(5);
        assertThat(mongoTemplate.find(Query.query(Criteria.where("tenantId").is(tenantId)), QuickReply.class))
                .extracting(QuickReply::getTitle).containsExactlyInAnyOrder("Horarios", "Envíos", "Medios de pago");
        assertThat(mongoTemplate.findById(tenantId, Tenant.class).getSettings().getRiskAlertMinutes()).isEqualTo(15);
        // Solo quedan las tres cuentas demo, activas y con la contraseña documentada
        assertThat(mongoTemplate.find(Query.query(Criteria.where("tenantId").is(tenantId)), User.class))
                .extracting(User::getEmail)
                .containsExactlyInAnyOrder(DemoData.OWNER_EMAIL, DemoData.AGENT_1_EMAIL, DemoData.AGENT_2_EMAIL);
        assertThat(users.findByEmail(DemoData.AGENT_1_EMAIL).orElseThrow().isActive()).isTrue();
        assertThat(authService.login(new LoginRequest(DemoData.AGENT_1_EMAIL, DemoData.DEMO_PASSWORD)).token())
                .isNotBlank();
        // Quien restauró sigue con su sesión: mismo usuario, mismo token
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(DemoData.OWNER_EMAIL));
    }

    @Test
    void anotherCompanyCannotRestoreTheDemoNorLoseItsOwnData() throws Exception {
        var other = authService.register(new RegisterRequest("Otra empresa", "Ana",
                "otra-" + UUID.randomUUID() + "@test.com", "s3cret-pass"));
        String demoTenant = users.findByEmail(DemoData.OWNER_EMAIL).orElseThrow().getTenantId();
        long demoConversations = count(Conversation.class, demoTenant);

        mockMvc.perform(post("/api/dev/demo/restore").header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_DEMO_COMPANY"));

        assertThat(count(Conversation.class, demoTenant)).isEqualTo(demoConversations);
        assertThat(users.findByEmail(other.user().email())).isPresent();
    }
}
