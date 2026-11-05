package com.unicanal.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.unicanal.Fixtures;
import com.unicanal.MutableClock;
import com.unicanal.MutableClockConfig;
import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.JwtService;
import com.unicanal.auth.JwtTestTokens;
import com.unicanal.config.AppProperties;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.LoginRequest;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.DeliveryStatusUpdater;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.channel.StatusUpdate;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.conversation.AssignmentService;
import com.unicanal.conversation.ConversationService;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.conversation.ReplyService;
import com.unicanal.conversation.dto.MessageResponse;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import com.unicanal.user.AgentService;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.dto.CreateAgentRequest;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * WebSocket de punta a punta con un cliente STOMP real contra el servidor en un puerto aleatorio:
 * autenticación en CONNECT (decisión 6), suscripción solo al propio tenant, SEND prohibido, contenido de
 * los eventos y cierre de la sesión de un agente desactivado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, MutableClockConfig.class})
class WebSocketIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final String PASSWORD = "s3cret-pass";

    @LocalServerPort
    private int port;
    @Autowired
    private AuthService authService;
    @Autowired
    private AgentService agentService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private InboundMessageProcessor processor;
    @Autowired
    private ReplyService replyService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private DeliveryStatusUpdater statusUpdater;
    @Autowired
    private MessageRepository messages;

    @Autowired
    private WebSocketSessionRegistry sessionRegistry;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private MutableClock clock;

    private final List<StompSession> opened = new ArrayList<>();

    @AfterEach
    void disconnectAll() {
        clock.reset();
        opened.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    }

    // --- utilidades ---

    private record Account(String token, AuthPrincipal principal) {
        String tenantId() {
            return principal.tenantId();
        }
    }

    private Account newCompany() {
        AuthResponse auth = authService.register(new RegisterRequest("Empresa " + UUID.randomUUID(), "Dueña",
                "ws-" + UUID.randomUUID() + "@test.com", PASSWORD));
        return new Account(auth.token(), new AuthPrincipal(auth.user().id(), auth.tenant().id(), Role.OWNER));
    }

    private Account newAgent(Account owner, String name) {
        String email = "ws-agent-" + UUID.randomUUID() + "@test.com";
        User agent = agentService.create(owner.tenantId(), new CreateAgentRequest(name, email, PASSWORD));
        return new Account(authService.login(new LoginRequest(email, PASSWORD)).token(),
                new AuthPrincipal(agent.getId(), owner.tenantId(), Role.AGENT));
    }

    /** Recoge los frames ERROR y los fallos de transporte de una sesión. */
    private static final class Recorder extends StompSessionHandlerAdapter {
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> transportErrors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String message = headers.getFirst("message");
            errors.add(message != null ? message : "");
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload,
                Throwable exception) {
            transportErrors.add(exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            transportErrors.add(exception);
        }
    }

    private record Connection(StompSession session, Recorder recorder) {
    }

    /** Conecta con la cabecera Authorization indicada (nula = sin cabecera). Devuelve null si el servidor rechaza. */
    private Connection connect(String authorization, Recorder recorder) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        StompHeaders connectHeaders = new StompHeaders();
        if (authorization != null) {
            connectHeaders.add("Authorization", authorization);
        }
        try {
            StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                    connectHeaders, recorder).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            opened.add(session);
            return new Connection(session, recorder);
        } catch (Exception e) {
            return null;
        }
    }

    private Connection connect(Account account) throws Exception {
        Connection connection = connect("Bearer " + account.token(), new Recorder());
        assertThat(connection).as("conexión aceptada").isNotNull();
        return connection;
    }

    private BlockingQueue<String> subscribe(Connection connection, String destination) {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        connection.session().subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return events;
    }

    /** Suscribe al tópico de la empresa y espera a que el servidor haya procesado la suscripción. */
    private BlockingQueue<String> subscribeToOwnTenant(Connection connection, Account account) throws Exception {
        BlockingQueue<String> events = subscribe(connection, RealtimeTopics.forTenant(account.tenantId()));
        // El broker simple no envía RECEIPT: se da un margen para que registre la suscripción
        TimeUnit.MILLISECONDS.sleep(300);
        return events;
    }

    /** Siguiente evento que cumple la condición (descarta los demás). */
    private static String awaitEvent(BlockingQueue<String> events, Predicate<String> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && condition.test(event)) {
                return event;
            }
        }
        throw new AssertionError("No llegó el evento esperado");
    }

    private static Predicate<String> ofType(String type) {
        return json -> type.equals(JsonPath.read(json, "$.type"));
    }

    private static Predicate<String> ofType(String type, String conversationId) {
        return ofType(type).and(json -> conversationId.equals(JsonPath.read(json, "$.conversationId")));
    }

    private String inbound(Account account, String contact) {
        return processor.process(account.tenantId(), new InboundMessage(ChannelType.SIMULATED, "cuenta", contact,
                "Cliente", "in-" + UUID.randomUUID(), MessageType.TEXT, "hola, ¿tienen envíos?", Instant.now()))
                .conversationId();
    }

    private static String poll(BlockingQueue<String> queue) throws InterruptedException {
        return queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // --- CONNECT ---

    @Test
    void connectWithoutTokenOrWithAnInvalidOneIsRejected() throws Exception {
        for (String authorization : new String[] {null, "Bearer no-es-un-jwt", "Basic abc"}) {
            Recorder recorder = new Recorder();
            Connection connection = connect(authorization, recorder);

            assertThat(connection).as("CONNECT con %s", authorization).isNull();
            assertThat(poll(recorder.errors)).isEqualTo("No autorizado: inicia sesión de nuevo");
        }
    }

    @Test
    void connectChecksInTheDatabaseThatTheUserIsActiveAndOfTheTokensTenant() throws Exception {
        Account owner = newCompany();
        Account ana = newAgent(owner, "Ana");
        agentService.deactivate(owner.tenantId(), ana.principal().userId());

        Recorder deactivated = new Recorder();
        assertThat(connect("Bearer " + ana.token(), deactivated)).isNull();
        assertThat(poll(deactivated.errors)).isEqualTo("No autorizado: inicia sesión de nuevo");

        // Token bien firmado de un usuario real, pero con el tenant de otra empresa
        Account other = newCompany();
        User forged = Fixtures.user(owner.principal().userId(), other.tenantId(), Role.OWNER, "x@test.com", "hash");
        Recorder wrongTenant = new Recorder();
        assertThat(connect("Bearer " + jwtService.issue(forged).value(), wrongTenant)).isNull();
        assertThat(poll(wrongTenant.errors)).isEqualTo("No autorizado: inicia sesión de nuevo");

        assertThat(connect(owner)).isNotNull();
    }

    // --- SUBSCRIBE y SEND ---

    @Test
    void subscribingToAnotherCompanysTopicIsRejectedAndNothingLeaks() throws Exception {
        Account a = newCompany();
        Account b = newCompany();
        Connection spy = connect(a);

        BlockingQueue<String> stolen = subscribe(spy, RealtimeTopics.forTenant(b.tenantId()));

        assertThat(poll(spy.recorder().errors)).isEqualTo("Solo puedes suscribirte a los eventos de tu empresa");
        inbound(b, "cliente-de-b");
        assertThat(stolen.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void otherDestinationsAreAlsoRejected() throws Exception {
        Account a = newCompany();
        for (String destination : new String[] {"/topic/tenants/" + a.tenantId(), "/topic/tenants/*/events",
                RealtimeTopics.forTenant(a.tenantId()) + "/extra", "/app/algo"}) {
            Connection connection = connect(a);
            subscribe(connection, destination);
            assertThat(poll(connection.recorder().errors)).as(destination)
                    .isEqualTo("Solo puedes suscribirte a los eventos de tu empresa");
        }
    }

    @Test
    void clientsCannotPublishFakeEventsToTheirColleagues() throws Exception {
        Account owner = newCompany();
        Account ana = newAgent(owner, "Ana");
        Connection listener = connect(owner);
        BlockingQueue<String> events = subscribeToOwnTenant(listener, owner);
        Connection attacker = connect(ana);

        attacker.session().send(RealtimeTopics.forTenant(owner.tenantId()),
                "{\"type\":\"ASSIGNED\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(poll(attacker.recorder().errors)).isEqualTo("Los clientes no pueden publicar mensajes");
        assertThat(events.poll(1, TimeUnit.SECONDS)).isNull();
    }

    // --- eventos ---

    @Test
    void aNewCustomerMessageReachesOnlyItsCompanyWithEverythingTheInboxNeeds() throws Exception {
        Account owner = newCompany();
        Account ana = newAgent(owner, "Ana");
        BlockingQueue<String> events = subscribeToOwnTenant(connect(owner), owner);
        Account other = newCompany();
        BlockingQueue<String> otherEvents = subscribeToOwnTenant(connect(other), other);

        String conversationId = inbound(owner, "cliente-nuevo");

        // Orden de publicación preservado: primero la asignación por turnos, después el mensaje
        String assigned = awaitEvent(events, ofType("ASSIGNED", conversationId));
        String event = poll(events);
        assertThat(event).isNotNull();
        assertThat((String) JsonPath.read(event, "$.type")).isEqualTo("MESSAGE_CREATED");
        assertThat((String) JsonPath.read(assigned, "$.conversation.assignedTo.id"))
                .isEqualTo(ana.principal().userId());
        assertThat((String) JsonPath.read(event, "$.message.direction")).isEqualTo("INBOUND");
        assertThat((String) JsonPath.read(event, "$.message.content")).isEqualTo("hola, ¿tienen envíos?");
        assertThat((String) JsonPath.read(event, "$.conversation.lastMessagePreview"))
                .isEqualTo("hola, ¿tienen envíos?");
        assertThat((String) JsonPath.read(event, "$.conversation.status")).isEqualTo("NEW");
        assertThat((Integer) JsonPath.read(event, "$.conversation.unreadCount")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(event, "$.conversation.atRisk")).isFalse();
        assertThat((String) JsonPath.read(event, "$.conversation.contact.name")).isEqualTo("Cliente");
        // La asignación por turnos ocurre antes de difundir: el evento ya trae el agente
        assertThat((String) JsonPath.read(event, "$.conversation.assignedTo.id")).isEqualTo(ana.principal().userId());
        assertThat((String) JsonPath.read(event, "$.conversation.assignedTo.name")).isEqualTo("Ana");

        assertThat(otherEvents.poll(1, TimeUnit.SECONDS)).as("otra empresa no recibe nada").isNull();
    }

    @Test
    void aReplyIsBroadcastOnlyAfterEveryWriteWithItsFinalDeliveryStatus() throws Exception {
        Account owner = newCompany();
        String conversationId = inbound(owner, "cliente-respuesta");
        BlockingQueue<String> events = subscribeToOwnTenant(connect(owner), owner);

        MessageResponse sent = replyService.reply(owner.principal(), conversationId, "¡Sí, a todo el país!");

        String event = awaitEvent(events, ofType("MESSAGE_CREATED", conversationId));
        // Nunca se difunde el PENDING intermedio: el evento sale tras guardar SENT, firstResponseAt y el estado
        assertThat((String) JsonPath.read(event, "$.message.id")).isEqualTo(sent.id());
        assertThat((String) JsonPath.read(event, "$.message.deliveryStatus")).isEqualTo("SENT");
        assertThat((String) JsonPath.read(event, "$.conversation.status")).isEqualTo("IN_PROGRESS");
        assertThat((Object) JsonPath.read(event, "$.conversation.firstResponseAt")).isNotNull();
        assertThat((String) JsonPath.read(event, "$.conversation.lastMessagePreview"))
                .isEqualTo("¡Sí, a todo el país!");
        assertThat(events.poll(500, TimeUnit.MILLISECONDS)).as("un solo evento por respuesta").isNull();

        // Estado de entrega que llega después por el webhook
        String externalId = messages.findById(sent.id()).orElseThrow().getExternalId();
        statusUpdater.apply(owner.tenantId(), new StatusUpdate(ChannelType.SIMULATED, "cuenta", externalId,
                DeliveryStatus.DELIVERED, Instant.now()));
        String delivered = awaitEvent(events, ofType("CONVERSATION_UPDATED", conversationId));
        assertThat((String) JsonPath.read(delivered, "$.message.id")).isEqualTo(sent.id());
        assertThat((String) JsonPath.read(delivered, "$.message.deliveryStatus")).isEqualTo("DELIVERED");
    }

    @Test
    void assignmentStatusReadAndSaleChangesAreBroadcast() throws Exception {
        Account owner = newCompany();
        String conversationId = inbound(owner, "cliente-cambios");
        Account ana = newAgent(owner, "Ana");
        BlockingQueue<String> events = subscribeToOwnTenant(connect(ana), ana);

        assignmentService.assign(owner.principal(), conversationId, ana.principal().userId());
        String assigned = awaitEvent(events, ofType("ASSIGNED", conversationId));
        assertThat((String) JsonPath.read(assigned, "$.conversation.assignedTo.id"))
                .isEqualTo(ana.principal().userId());

        conversationService.markRead(owner.tenantId(), conversationId);
        String read = awaitEvent(events, ofType("CONVERSATION_UPDATED", conversationId));
        assertThat((Integer) JsonPath.read(read, "$.conversation.unreadCount")).isZero();

        conversationService.markSale(owner.tenantId(), conversationId, true, new BigDecimal("80.00"));
        String sale = awaitEvent(events, ofType("CONVERSATION_UPDATED", conversationId));
        assertThat((Boolean) JsonPath.read(sale, "$.conversation.isSale")).isTrue();

        conversationService.changeStatus(owner.tenantId(), conversationId, ConversationStatus.RESOLVED);
        String resolved = awaitEvent(events, ofType("CONVERSATION_UPDATED", conversationId));
        assertThat((String) JsonPath.read(resolved, "$.conversation.status")).isEqualTo("RESOLVED");
        assertThat((Boolean) JsonPath.read(resolved, "$.conversation.open")).isFalse();
    }

    // --- vencimiento del token ---

    @Test
    void theSessionIsClosedWhenTheJwtItConnectedWithExpires() throws Exception {
        AuthResponse auth = authService.register(new RegisterRequest("Empresa " + UUID.randomUUID(), "Dueña",
                "ws-exp-" + UUID.randomUUID() + "@test.com", PASSWORD));
        Account owner = new Account(auth.token(),
                new AuthPrincipal(auth.user().id(), auth.tenant().id(), Role.OWNER));
        // Otra sesión con un token que vence mucho más tarde (mismo secreto, otra duración)
        Account other = newCompany();
        User otherUser = Fixtures.user(other.principal().userId(), other.tenantId(), Role.OWNER, "x@test.com", "h");
        String longLived = JwtTestTokens.issue(appProperties, 600, otherUser).value();
        Connection expiring = connect(owner);
        Connection fresh = connect("Bearer " + longLived, new Recorder());
        assertThat(fresh).isNotNull();

        // Un segundo antes del vencimiento sigue abierta
        clock.set(auth.expiresAt().minusSeconds(1));
        sessionRegistry.closeExpiredSessions();
        assertThat(expiring.session().isConnected()).isTrue();

        // Al vencer, la tarea periódica la cierra; la de un token vigente no se toca
        clock.set(auth.expiresAt());
        sessionRegistry.closeExpiredSessions();
        awaitDisconnected(expiring);
        assertThat(fresh.session().isConnected()).isTrue();
    }

    private static void awaitDisconnected(Connection connection) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (connection.session().isConnected() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(connection.session().isConnected()).as("sesión cerrada").isFalse();
    }

    // --- desactivación ---

    @Test
    void deactivatingAConnectedAgentClosesTheirSessionButNotTheOthers() throws Exception {
        Account owner = newCompany();
        Account ana = newAgent(owner, "Ana");
        Connection ownerConnection = connect(owner);
        BlockingQueue<String> ownerEvents = subscribeToOwnTenant(ownerConnection, owner);
        Connection anaConnection = connect(ana);
        BlockingQueue<String> anaEvents = subscribeToOwnTenant(anaConnection, ana);
        String conversationId = inbound(owner, "cliente-de-ana");
        awaitEvent(anaEvents, ofType("MESSAGE_CREATED", conversationId));
        awaitEvent(ownerEvents, ofType("MESSAGE_CREATED", conversationId)); // descarta el ASSIGNED inicial
        anaEvents.clear();

        agentService.deactivate(owner.tenantId(), ana.principal().userId());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (anaConnection.session().isConnected() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(anaConnection.session().isConnected()).as("sesión de la agente desactivada").isFalse();
        assertThat(ownerConnection.session().isConnected()).isTrue();
        // El dueño ve cómo el chat de Ana queda sin asignar; Ana ya no recibe nada
        String unassigned = awaitEvent(ownerEvents, ofType("ASSIGNED", conversationId));
        assertThat((Object) JsonPath.read(unassigned, "$.conversation.assignedTo")).isNull();
        assertThat(anaEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // Y no puede volver a conectarse con su token
        Recorder retry = new Recorder();
        assertThat(connect("Bearer " + ana.token(), retry)).isNull();
    }
}
