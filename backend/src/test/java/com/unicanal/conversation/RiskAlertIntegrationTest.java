package com.unicanal.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.MutableClock;
import com.unicanal.MutableClockConfig;
import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.conversation.ConversationTestSupport.Session;
import com.unicanal.user.AgentService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Alerta "cero mensajes perdidos" con MongoDB real y un reloj controlado: el tiempo avanza a voluntad,
 * sin esperas reales, y la pasada programada se invoca a mano (el scheduler está desactivado en pruebas).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfig.class, MutableClockConfig.class})
class RiskAlertIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private InboundMessageProcessor processor;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private RiskAlertService riskAlert;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ApplicationEvents events;

    private ConversationTestSupport support;
    private Instant t0;

    @BeforeEach
    void setUp() {
        support = new ConversationTestSupport(mockMvc, authService, agentService, processor, mongoTemplate);
        t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        clock.set(t0);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Pasada de la alerta a los {@code minutes} minutos (y {@code seconds} segundos) de t0. */
    private void checkAt(long minutes, long seconds) {
        clock.set(t0.plus(Duration.ofMinutes(minutes)).plusSeconds(seconds));
        events.clear();
        riskAlert.checkAllTenants();
    }

    private void checkAt(long minutes) {
        checkAt(minutes, 0);
    }

    private String customerWrites(Session session, ChannelType channel, String contact, long minuteOffset) {
        return support.inbound(session, channel, contact, t0.plus(Duration.ofMinutes(minuteOffset)));
    }

    private String customerWrites(Session session, ChannelType channel) {
        return customerWrites(session, channel, "cli-" + UUID.randomUUID(), 0);
    }

    private void replyAt(Session session, String conversationId, long minuteOffset, String expectedStatus)
            throws Exception {
        clock.set(t0.plus(Duration.ofMinutes(minuteOffset)));
        support.reply(session, conversationId, "respuesta").andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryStatus").value(expectedStatus));
    }

    private Conversation conversation(String id) {
        return conversations.findById(id).orElseThrow();
    }

    private long updateEventsFor(String conversationId) {
        return events.stream(ConversationUpdatedEvent.class)
                .filter(event -> event.conversationId().equals(conversationId)).count();
    }

    // --- marcado ---

    @Test
    void aChatIsMarkedWhenTheThresholdIsReachedOnlyOnceAndWithASingleEvent() {
        Session owner = support.newCompany(); // 15 minutos por defecto
        String id = customerWrites(owner, ChannelType.SIMULATED);

        checkAt(14, 59);
        assertThat(conversation(id).isAtRisk()).isFalse();
        assertThat(updateEventsFor(id)).isZero();

        checkAt(15);
        Conversation marked = conversation(id);
        assertThat(marked.isAtRisk()).isTrue();
        assertThat(marked.getAtRiskSince()).isEqualTo(t0.plus(Duration.ofMinutes(15)));
        assertThat(updateEventsFor(id)).isEqualTo(1);

        // Pasadas posteriores no la vuelven a tocar ni a notificar
        checkAt(40);
        assertThat(conversation(id).getAtRiskSince()).isEqualTo(t0.plus(Duration.ofMinutes(15)));
        assertThat(updateEventsFor(id)).isZero();
    }

    @Test
    void onlyOpenChatsWaitingForAReplyAreMarked() throws Exception {
        Session owner = support.newCompany();
        String waiting = customerWrites(owner, ChannelType.SIMULATED);
        String answered = customerWrites(owner, ChannelType.SIMULATED);
        replyAt(owner, answered, 1, "SENT");
        String resolved = customerWrites(owner, ChannelType.SIMULATED);
        support.changeStatus(owner, resolved, "RESOLVED").andExpect(status().isOk());
        String failedReply = customerWrites(owner, ChannelType.WHATSAPP);
        replyAt(owner, failedReply, 1, "FAILED"); // el canal falla (localhost:9): no cuenta como respuesta
        String wroteAgain = customerWrites(owner, ChannelType.SIMULATED, "insistente", 0);
        replyAt(owner, wroteAgain, 1, "SENT");
        customerWrites(owner, ChannelType.SIMULATED, "insistente", 2);
        Session other = support.newCompany();
        String otherCompany = customerWrites(other, ChannelType.SIMULATED);

        checkAt(16);

        assertThat(conversation(waiting).isAtRisk()).isTrue();
        assertThat(conversation(failedReply).isAtRisk()).isTrue();
        assertThat(conversation(otherCompany).isAtRisk()).isTrue(); // cada empresa con su propia pasada
        assertThat(conversation(answered).isAtRisk()).isFalse();
        assertThat(conversation(resolved).isAtRisk()).isFalse();
        assertThat(conversation(wroteAgain).isAtRisk()).as("volvió a escribir en el minuto 2").isFalse();
        assertThat(updateEventsFor(answered) + updateEventsFor(resolved) + updateEventsFor(wroteAgain)).isZero();

        checkAt(17);
        assertThat(conversation(wroteAgain).isAtRisk()).isTrue();
        assertThat(updateEventsFor(wroteAgain)).isEqualTo(1);
        assertThat(updateEventsFor(waiting)).isZero();
    }

    // --- limpieza ---

    @Test
    void theAlertIsClearedBySuccessfulRepliesAndByResolvingButNotByFailedSends() throws Exception {
        Session owner = support.newCompany();
        String replied = customerWrites(owner, ChannelType.SIMULATED);
        String resolved = customerWrites(owner, ChannelType.SIMULATED);
        String failed = customerWrites(owner, ChannelType.WHATSAPP);
        checkAt(15);
        assertThat(conversation(replied).isAtRisk()).isTrue();

        replyAt(owner, replied, 20, "SENT");
        support.changeStatus(owner, resolved, "RESOLVED").andExpect(status().isOk())
                .andExpect(jsonPath("$.atRisk").value(false))
                .andExpect(jsonPath("$.atRiskSince").doesNotExist());
        replyAt(owner, failed, 20, "FAILED");

        assertThat(conversation(replied).isAtRisk()).isFalse();
        assertThat(conversation(replied).getAtRiskSince()).isNull();
        assertThat(conversation(resolved).isAtRisk()).isFalse();
        assertThat(conversation(resolved).getAtRiskSince()).isNull();
        assertThat(conversation(failed).isAtRisk()).isTrue();

        // Si el cliente vuelve a escribir y nadie responde, vuelve a entrar en riesgo con su nueva fecha
        customerWrites(owner, ChannelType.SIMULATED, contactExternalIdOf(replied), 30);
        checkAt(45);
        assertThat(conversation(replied).isAtRisk()).isTrue();
        assertThat(conversation(replied).getAtRiskSince()).isEqualTo(t0.plus(Duration.ofMinutes(45)));
    }

    private String contactExternalIdOf(String conversationId) {
        String contactId = conversation(conversationId).getContactId();
        return mongoTemplate.findById(contactId, com.unicanal.contact.Contact.class).getIdentities().get(0)
                .externalId();
    }

    // --- configuración por empresa ---

    @Test
    void eachCompanyUsesItsOwnThresholdAndAChangeAppliesOnTheNextPass() throws Exception {
        Session fast = support.newCompany();
        Session slow = support.newCompany();
        support.putAs(fast, "/api/tenant/settings", "{\"riskAlertMinutes\":5}").andExpect(status().isOk());
        String fastChat = customerWrites(fast, ChannelType.SIMULATED);
        String slowChat = customerWrites(slow, ChannelType.SIMULATED);

        checkAt(5);
        assertThat(conversation(fastChat).isAtRisk()).isTrue();
        assertThat(conversation(slowChat).isAtRisk()).as("sigue con 15 minutos").isFalse();

        support.putAs(slow, "/api/tenant/settings", "{\"riskAlertMinutes\":3}").andExpect(status().isOk());
        checkAt(5);
        assertThat(conversation(slowChat).isAtRisk()).as("la siguiente pasada usa el valor nuevo").isTrue();
        assertThat(updateEventsFor(slowChat)).isEqualTo(1);
        assertThat(updateEventsFor(fastChat)).isZero();
    }

    @Test
    void onlyTheOwnerReadsAndChangesTheSettingsWithinLimits() throws Exception {
        Session owner = support.newCompany();
        Session agent = support.newAgent(owner, "Ana");
        Session other = support.newCompany();

        support.getAs(owner, "/api/tenant/settings").andExpect(status().isOk())
                .andExpect(jsonPath("$.riskAlertMinutes").value(15));
        support.putAs(owner, "/api/tenant/settings", "{\"riskAlertMinutes\":1440}").andExpect(status().isOk())
                .andExpect(jsonPath("$.riskAlertMinutes").value(1440));
        support.putAs(owner, "/api/tenant/settings", "{\"riskAlertMinutes\":1}").andExpect(status().isOk());

        for (String invalid : new String[] {"{\"riskAlertMinutes\":0}", "{\"riskAlertMinutes\":1441}", "{}"}) {
            support.putAs(owner, "/api/tenant/settings", invalid).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.riskAlertMinutes").exists());
        }
        support.getAs(agent, "/api/tenant/settings").andExpect(status().isForbidden());
        support.putAs(agent, "/api/tenant/settings", "{\"riskAlertMinutes\":30}").andExpect(status().isForbidden());

        support.getAs(owner, "/api/tenant/settings").andExpect(jsonPath("$.riskAlertMinutes").value(1));
        support.getAs(other, "/api/tenant/settings").andExpect(jsonPath("$.riskAlertMinutes").value(15));
    }
}
