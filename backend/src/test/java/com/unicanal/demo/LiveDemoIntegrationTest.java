package com.unicanal.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.MutableClock;
import com.unicanal.MutableClockConfig;
import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelType;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.demo.LiveDemoScript.Kind;
import com.unicanal.demo.ManualDemoScheduler.Task;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Demo en vivo con reloj controlable y planificador manual: cada "tick" adelanta el reloj exactamente lo que se
 * programó y ejecuta el mensaje, sin esperas reales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfig.class, ManualDemoScheduler.Config.class, MutableClockConfig.class})
class LiveDemoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private ManualDemoScheduler scheduler;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private ContactRepository contacts;
    @Autowired
    private LiveDemoService liveDemo;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        scheduler.clear();
        clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private AuthResponse newCompany() {
        return authService.register(new RegisterRequest("Tienda " + UUID.randomUUID(), "Dueña",
                "live-" + UUID.randomUUID() + "@test.com", "s3cret-pass"));
    }

    private ResultActions call(AuthResponse who, String method, String url) throws Exception {
        var request = "GET".equals(method) ? get(url) : post(url);
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.token()));
    }

    /** Un tick: el reloj avanza lo que se programó y se ejecuta el mensaje. */
    private void tick() {
        // Los temporizadores cancelados (de una demo detenida) se disparan sin efecto
        while (scheduler.pending().get(0).cancelled()) {
            scheduler.runNext();
        }
        Task next = scheduler.pending().get(0);
        clock.advance(next.delay());
        scheduler.runNext();
    }

    private List<Message> inboundOf(String tenantId) {
        return mongoTemplate.find(Query.query(Criteria.where("tenantId").is(tenantId)
                .and("direction").is(Direction.INBOUND)), Message.class).stream()
                .sorted(Comparator.comparing(Message::getTimestamp)).toList();
    }

    @Test
    void theScriptSeparatesMessagesFromPeopleWithAboutOneFollowUpEveryFour() {
        List<LiveDemoScript.Line> lines = LiveDemoScript.LINES;
        assertThat(lines).hasSizeGreaterThanOrEqualTo(15);
        long followUps = lines.stream().filter(line -> line.kind() == Kind.FOLLOW_UP).count();
        // Aproximadamente 1 de cada 4
        assertThat(followUps).isBetween((long) lines.size() / 5, (long) Math.ceil(lines.size() / 3.0));
        assertThat(lines.get(0).kind()).as("la demo empieza con un cliente nuevo").isEqualTo(Kind.NEW);
        String all = lines.stream().map(LiveDemoScript.Line::text).collect(Collectors.joining(" ")).toLowerCase();
        assertThat(all).contains("talla", "nequi", "envío", "cotizar", "sábado", "cantidad");
        assertThat(DemoCustomers.FIRST_NAMES).hasSizeGreaterThanOrEqualTo(30).doesNotHaveDuplicates();
        assertThat(DemoCustomers.LAST_NAMES).hasSizeGreaterThanOrEqualTo(30).doesNotHaveDuplicates();
    }

    @Test
    void fifteenTicksProduceFifteenMessagesInOrderFromAtLeastTenDifferentCustomersOnTheThreeChannels()
            throws Exception {
        AuthResponse owner = newCompany();
        String tenantId = owner.tenant().id();
        // Un cliente que ya está en la bandeja: su nombre no puede salir para un cliente nuevo de la demo
        contacts.save(new Contact(tenantId, "Valentina Ríos", ChannelType.WHATSAPP, "573000000000"));

        call(owner, "POST", "/api/dev/live-demo/start").andExpect(status().isOk());
        for (int i = 0; i < 15; i++) {
            assertThat(scheduler.pending()).as("un solo temporizador antes del tick %d", i + 1).hasSize(1);
            assertThat(scheduler.pending().get(0).delay()).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(40));
            tick();
        }

        List<Message> received = inboundOf(tenantId);
        // 15 mensajes, en el orden del guion, sin duplicados
        assertThat(received).hasSize(15);
        assertThat(received).extracting(Message::getContent).containsExactlyElementsOf(
                LiveDemoScript.LINES.subList(0, 15).stream().map(LiveDemoScript.Line::text).toList());
        assertThat(received).extracting(Message::getId).doesNotHaveDuplicates();
        assertThat(received).extracting(Message::getTimestamp).isSorted().doesNotHaveDuplicates();

        Map<String, Conversation> conversationById = mongoTemplate.find(
                Query.query(Criteria.where("tenantId").is(tenantId)), Conversation.class).stream()
                .collect(Collectors.toMap(Conversation::getId, Function.identity()));
        Map<String, Contact> contactById = mongoTemplate.find(Query.query(Criteria.where("tenantId").is(tenantId)),
                Contact.class).stream().collect(Collectors.toMap(Contact::getId, Function.identity()));
        List<Contact> authors = received.stream()
                .map(m -> contactById.get(conversationById.get(m.getConversationId()).getContactId())).toList();

        // Al menos 10 clientes distintos, con nombres que no se repiten ni chocan con la bandeja
        List<Contact> demoCustomers = authors.stream().distinct().toList();
        assertThat(demoCustomers).hasSizeGreaterThanOrEqualTo(10);
        assertThat(demoCustomers).extracting(Contact::getName).doesNotHaveDuplicates().doesNotContain("Valentina Ríos");
        assertThat(demoCustomers).extracting(Contact::getName)
                .allSatisfy(name -> assertThat(name.split(" ", 2)[0]).isIn(firstWords()));
        // Los tres canales
        assertThat(demoCustomers.stream().flatMap(c -> c.getIdentities().stream()).map(i -> i.channel()).toList())
                .contains(ChannelType.WHATSAPP, ChannelType.INSTAGRAM, ChannelType.FACEBOOK);
        // Cada continuación la escribe un cliente que la demo ya había abierto antes, en su misma conversación
        for (int i = 0; i < 15; i++) {
            if (LiveDemoScript.LINES.get(i).kind() == Kind.FOLLOW_UP) {
                Contact author = authors.get(i);
                assertThat(authors.subList(0, i)).as("continuación del mensaje %d", i + 1).contains(author);
            } else {
                assertThat(authors.subList(0, i)).as("cliente nuevo del mensaje %d", i + 1)
                        .doesNotContain(authors.get(i));
            }
        }
        call(owner, "GET", "/api/dev/live-demo")
                .andExpect(jsonPath("$.messagesSent").value(15))
                .andExpect(jsonPath("$.currentStep").value(15))
                .andExpect(jsonPath("$.scriptLength").value(LiveDemoScript.size()))
                .andExpect(jsonPath("$.lastCustomer").value(authors.get(14).getName()));
    }

    private static List<String> firstWords() {
        return DemoCustomers.FIRST_NAMES.stream().map(name -> name.split(" ")[0]).toList();
    }

    @Test
    void startingTwiceAtTheSameTimeStillProducesOneMessagePerTick() throws Exception {
        AuthResponse owner = newCompany();
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call(owner, "POST", "/api/dev/live-demo/start").andReturn().getResponse().getStatus();
                }));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        call(owner, "POST", "/api/dev/live-demo/start").andExpect(status().isOk());

        assertThat(scheduler.pending()).as("un único temporizador").hasSize(1);
        for (int i = 1; i <= 3; i++) {
            tick();
            assertThat(inboundOf(owner.tenant().id())).as("tras %d ticks", i).hasSize(i);
            assertThat(scheduler.pending()).hasSize(1);
        }
    }

    @Test
    void stoppingAndStartingAgainContinuesTheScriptWithNewPeople() throws Exception {
        AuthResponse owner = newCompany();
        String tenantId = owner.tenant().id();
        call(owner, "POST", "/api/dev/live-demo/start");
        tick();
        tick();
        call(owner, "POST", "/api/dev/live-demo/stop").andExpect(jsonPath("$.running").value(false));

        call(owner, "POST", "/api/dev/live-demo/start").andExpect(jsonPath("$.running").value(true));
        tick();

        List<Message> received = inboundOf(tenantId);
        // Sigue en el mensaje 3: no repite el 1 y el 2 (antes la posición se reiniciaba en cada inicio)
        assertThat(received).extracting(Message::getContent).containsExactly(
                LiveDemoScript.LINES.get(0).text(), LiveDemoScript.LINES.get(1).text(),
                LiveDemoScript.LINES.get(2).text());
        assertThat(received).extracting(Message::getConversationId).doesNotHaveDuplicates();
        call(owner, "GET", "/api/dev/live-demo").andExpect(jsonPath("$.currentStep").value(3));
    }

    @Test
    void stopCancelsTheOnlyTimerAndEachCompanyHasItsOwnDemo() throws Exception {
        AuthResponse a = newCompany();
        AuthResponse b = newCompany();
        call(a, "POST", "/api/dev/live-demo/start").andExpect(status().isOk());
        Task pendingOfA = scheduler.pending().get(0);

        call(b, "GET", "/api/dev/live-demo").andExpect(jsonPath("$.running").value(false));
        call(a, "POST", "/api/dev/live-demo/stop").andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.nextMessageAt").doesNotExist());

        assertThat(pendingOfA.cancelled()).isTrue();
        // Aunque el temporizador se disparara igual, una demo detenida no envía nada ni programa otro
        scheduler.runNext();
        assertThat(inboundOf(a.tenant().id())).isEmpty();
        assertThat(scheduler.pending()).isEmpty();
    }

    @Test
    void aFollowUpWithoutAnyOpenDemoConversationIsWrittenByANewCustomer() throws Exception {
        AuthResponse owner = newCompany();
        String tenantId = owner.tenant().id();
        liveDemo.start(tenantId);
        tick();
        tick();
        tick();
        // El presentador resuelve los tres chats de la demo antes de la primera continuación
        mongoTemplate.updateMulti(Query.query(Criteria.where("tenantId").is(tenantId)),
                org.springframework.data.mongodb.core.query.Update.update("open", false).set("status", "RESOLVED"),
                Conversation.class);

        tick();

        List<Message> received = inboundOf(tenantId);
        assertThat(received.get(3).getContent()).isEqualTo(LiveDemoScript.LINES.get(3).text());
        // Llegó igual, de un cliente nuevo, en una conversación nueva
        assertThat(mongoTemplate.count(Query.query(Criteria.where("tenantId").is(tenantId)), Contact.class))
                .isEqualTo(4);
    }
}
