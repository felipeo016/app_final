package com.unicanal.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.channel.ChannelAdapter;
import com.unicanal.channel.ChannelRegistry;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.channel.simulated.SimulatedChannelAdapter;
import com.unicanal.common.ApiException;
import com.unicanal.conversation.ConversationTestSupport.Session;
import com.unicanal.conversation.dto.MessageResponse;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.user.AgentService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Envío de respuestas con MongoDB real y SIN el perfil dev:
 * <ul>
 *   <li>SIMULATED: adaptador simulado (siempre disponible).</li>
 *   <li>FACEBOOK: adaptador de prueba que registra en qué estado estaba el mensaje al llamarlo y puede fallar.</li>
 *   <li>WHATSAPP: adaptador real apuntando a un puerto cerrado (localhost:9), así que el envío falla.</li>
 *   <li>INSTAGRAM: sin adaptador; fuera de dev debe fallar con un error claro y nunca usar el simulado.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, ReplyIntegrationTest.FakeFacebook.class})
class ReplyIntegrationTest {

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
    private MessageRepository messages;
    @Autowired
    private ReplyService replyService;
    @Autowired
    private ChannelRegistry channelRegistry;
    @Autowired
    private RecordingFacebookAdapter facebook;

    private ConversationTestSupport support;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeFacebook {

        @Bean
        RecordingFacebookAdapter recordingFacebookAdapter(MessageRepository messages) {
            return new RecordingFacebookAdapter(messages);
        }
    }

    /** Canal de prueba: anota el estado guardado del mensaje en el momento exacto en que se le llama. */
    static class RecordingFacebookAdapter implements ChannelAdapter {

        private final MessageRepository messages;
        final List<DeliveryStatus> statusSeenWhenCalled = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException failure;
        /** Se ejecuta dentro del envío: simula lo que otro agente hace mientras tanto. */
        volatile Runnable duringSend;

        RecordingFacebookAdapter(MessageRepository messages) {
            this.messages = messages;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.FACEBOOK;
        }

        @Override
        public SendResult sendMessage(OutboundMessage message) {
            messages.findByTenantIdAndConversationId(message.tenantId(), message.conversationId(),
                            PageRequest.of(0, 100)).stream()
                    .filter(m -> m.getDirection() == Direction.OUTBOUND && message.text().equals(m.getContent()))
                    .forEach(m -> statusSeenWhenCalled.add(m.getDeliveryStatus()));
            Runnable hook = duringSend;
            if (hook != null) {
                hook.run();
            }
            if (failure != null) {
                throw failure;
            }
            return new SendResult("fb-" + UUID.randomUUID(), DeliveryStatus.SENT);
        }

        @Override
        public WebhookPayload parseWebhook(byte[] rawBody) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        support = new ConversationTestSupport(mockMvc, authService, agentService, processor, mongoTemplate);
        facebook.failure = null;
        facebook.duringSend = null;
        facebook.statusSeenWhenCalled.clear();
    }

    private Conversation conversation(String id) {
        return conversations.findById(id).orElseThrow();
    }

    private List<Message> outbound(Session session, String conversationId) {
        return messages.findByTenantIdAndConversationId(session.tenantId(), conversationId, PageRequest.of(0, 100))
                .stream().filter(m -> m.getDirection() == Direction.OUTBOUND).toList();
    }

    // --- envío correcto ---

    @Test
    void theMessageIsStoredAsPendingBeforeCallingTheChannelAndThenSent() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.FACEBOOK);

        support.reply(owner, id, "Hola, ¿en qué te ayudo?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryStatus").value("SENT"))
                .andExpect(jsonPath("$.direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.sentBy").value(owner.userId()))
                .andExpect(jsonPath("$.failureReason").doesNotExist());

        assertThat(facebook.statusSeenWhenCalled).containsExactly(DeliveryStatus.PENDING);
        Message stored = outbound(owner, id).get(0);
        assertThat(stored.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(stored.getExternalId()).startsWith("fb-");
        Conversation conversation = conversation(id);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
        assertThat(conversation.getFirstResponseAt()).isEqualTo(stored.getTimestamp());
        assertThat(conversation.getLastOutboundAt()).isEqualTo(stored.getTimestamp());
        assertThat(conversation.getLastMessagePreview()).isEqualTo("Hola, ¿en qué te ayudo?");
    }

    @Test
    void laterRepliesKeepTheFirstResponseTime() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED);
        support.reply(owner, id, "primera").andExpect(status().isCreated());
        Instant first = conversation(id).getFirstResponseAt();
        support.changeStatus(owner, id, "NEW").andExpect(status().isOk());

        support.reply(owner, id, "segunda").andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryStatus").value("SENT"));

        Conversation conversation = conversation(id);
        assertThat(conversation.getFirstResponseAt()).isEqualTo(first);
        // Ya hubo primera respuesta, pero NEW → IN_PROGRESS se aplica a cualquier envío mientras siga en NEW
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
    }

    // --- envío fallido: el agente lo ve ---

    @Test
    void aChannelErrorLeavesTheMessageFailedWithTheReasonAndVisibleInTheHistory() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.WHATSAPP); // localhost:9 → conexión rechazada

        support.reply(owner, id, "¿sigues ahí?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("No se pudo enviar el mensaje por WhatsApp"));

        support.getAs(owner, "/api/conversations/" + id + "/messages")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("¿sigues ahí?"))
                .andExpect(jsonPath("$.items[0].deliveryStatus").value("FAILED"))
                .andExpect(jsonPath("$.items[0].failureReason").value("No se pudo enviar el mensaje por WhatsApp"))
                .andExpect(jsonPath("$.items[1].direction").value("INBOUND"));
        Conversation conversation = conversation(id);
        // Un envío fallido no cuenta como respuesta al cliente
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.NEW);
        assertThat(conversation.getFirstResponseAt()).isNull();
        assertThat(conversation.getLastOutboundAt()).isNull();
        assertThat(outbound(owner, id)).singleElement()
                .satisfies(m -> assertThat(m.getExternalId()).isNull());
    }

    @Test
    void anUnexpectedChannelExceptionAlsoEndsAsFailedWithAGenericReason() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.FACEBOOK);
        facebook.failure = new IllegalStateException("detalle interno que no debe mostrarse");

        support.reply(owner, id, "hola").andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Error inesperado del canal al enviar el mensaje"));
        assertThat(facebook.statusSeenWhenCalled).containsExactly(DeliveryStatus.PENDING);
    }

    // --- validaciones previas: no se guarda nada ---

    @Test
    void outsideDevAChannelWithoutAdapterFailsClearlyAndNeverUsesTheSimulatedOne() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.INSTAGRAM);

        support.reply(owner, id, "hola")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "No se puede enviar por INSTAGRAM: el canal todavía no tiene integración"));

        assertThat(outbound(owner, id)).isEmpty();
        assertThat(conversation(id).getStatus()).isEqualTo(ConversationStatus.NEW);
        // El registro real de la aplicación (sin perfil dev) tampoco cae al simulado
        assertThatThrownBy(() -> channelRegistry.adapterFor(ChannelType.INSTAGRAM))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED));
        assertThat(channelRegistry.adapterFor(ChannelType.WHATSAPP)).isNotInstanceOf(SimulatedChannelAdapter.class);
    }

    @Test
    void whatsappOutsideThe24HourWindowIsRejectedWithoutStoringAnything() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.WHATSAPP, "573001112222",
                Instant.now().minus(Duration.ofHours(25)));

        support.getAs(owner, "/api/conversations/" + id).andExpect(jsonPath("$.replyWindowOpen").value(false));
        support.reply(owner, id, "hola")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("WHATSAPP_WINDOW_CLOSED"));
        assertThat(outbound(owner, id)).isEmpty();
    }

    @Test
    void aResolvedConversationMustBeReopenedBeforeReplying() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED);
        support.changeStatus(owner, id, "RESOLVED").andExpect(status().isOk());

        support.reply(owner, id, "hola").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_RESOLVED"));
        assertThat(outbound(owner, id)).isEmpty();
    }

    @Test
    void anotherCompanysConversationDoesNotExistForMe() throws Exception {
        Session a = support.newCompany();
        Session b = support.newCompany();
        String id = support.inbound(a, ChannelType.SIMULATED);

        support.reply(b, id, "hola").andExpect(status().isNotFound());
        support.getAs(b, "/api/conversations/" + id).andExpect(status().isNotFound());
        support.getAs(b, "/api/conversations/" + id + "/messages").andExpect(status().isNotFound());
        assertThat(outbound(a, id)).isEmpty();
    }

    @Test
    void emptyTextIsAValidationError() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED);

        support.postAs(owner, "/api/conversations/" + id + "/messages", "{\"text\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.text").exists());
    }

    // --- concurrencia: dos agentes responden a la vez ---

    @Test
    void concurrentFirstRepliesKeepTheEarliestResponseAndMoveToInProgressOnce() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        Session luis = support.newAgent(owner, "Luis");
        String id = support.inbound(owner, ChannelType.SIMULATED);

        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<MessageResponse>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Session who = i % 2 == 0 ? ana : luis;
                String text = "respuesta " + i;
                Callable<MessageResponse> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return replyService.reply(who.principal(), id, text);
                };
                results.add(pool.submit(task));
            }
            for (Future<MessageResponse> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Message> sent = outbound(owner, id);
        assertThat(sent).hasSize(threads).allSatisfy(m -> assertThat(m.getDeliveryStatus())
                .isEqualTo(DeliveryStatus.SENT));
        Instant earliest = sent.stream().map(Message::getTimestamp).min(Comparator.naturalOrder()).orElseThrow();
        Instant latest = sent.stream().map(Message::getTimestamp).max(Comparator.naturalOrder()).orElseThrow();
        Conversation conversation = conversation(id);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
        assertThat(conversation.getFirstResponseAt()).isEqualTo(earliest);
        assertThat(conversation.getLastOutboundAt()).isEqualTo(latest);
    }

    @Test
    void aStatusChangedWhileSendingIsNotOverwrittenByTheReply() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.FACEBOOK);
        // Mientras el canal "envía", otro agente resuelve la conversación
        facebook.duringSend = () -> mongoTemplate.updateFirst(ConversationService.byId(owner.tenantId(), id),
                Update.update("status", ConversationStatus.RESOLVED).set("open", false), Conversation.class);

        support.reply(owner, id, "justo a tiempo").andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryStatus").value("SENT"));

        // NEW → IN_PROGRESS es condicional: no reabre ni pisa el RESOLVED del otro agente
        Conversation conversation = conversation(id);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(conversation.isOpen()).isFalse();
        assertThat(conversation.getFirstResponseAt()).isNotNull();
    }

    @Test
    void anEarlierFirstResponseRecordedWhileSendingIsKept() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.FACEBOOK);
        Instant earlier = Instant.now().minus(Duration.ofSeconds(30)).truncatedTo(ChronoUnit.MILLIS);
        // Simula la respuesta de otro agente, más temprana, que termina mientras esta se envía
        facebook.duringSend = () -> mongoTemplate.updateFirst(ConversationService.byId(owner.tenantId(), id),
                Update.update("firstResponseAt", earlier).set("status", ConversationStatus.IN_PROGRESS),
                Conversation.class);

        support.reply(owner, id, "más tarde").andExpect(status().isCreated());

        assertThat(conversation(id).getFirstResponseAt()).isEqualTo(earlier);
    }

    // --- notas internas ---

    @Test
    void internalNotesAreNotSentAndDoNotCountAsResponse() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.FACEBOOK);

        support.postAs(owner, "/api/conversations/" + id + "/notes", "{\"text\":\"cliente VIP\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INTERNAL_NOTE"))
                .andExpect(jsonPath("$.deliveryStatus").doesNotExist());

        assertThat(facebook.statusSeenWhenCalled).isEmpty();
        Conversation conversation = conversation(id);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.NEW);
        assertThat(conversation.getFirstResponseAt()).isNull();
        assertThat(conversation.getLastMessagePreview()).isEqualTo("hola");
    }
}
