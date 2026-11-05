package com.unicanal.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.conversation.ConversationTestSupport.Session;
import com.unicanal.user.AgentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Asignación, cambio de estado/reapertura, respuestas rápidas, bandeja y perfil del contacto. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConversationManagementIntegrationTest {

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

    private ConversationTestSupport support;

    @BeforeEach
    void setUp() {
        support = new ConversationTestSupport(mockMvc, authService, agentService, processor, mongoTemplate);
    }

    private String assigneeOf(String conversationId) {
        return conversations.findById(conversationId).orElseThrow().getAssignedTo();
    }

    // --- asignación: OWNER ---

    @Test
    void theOwnerCanAssignToAnyActiveAgentAndUnassign() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        Session luis = support.newAgent(owner, "Luis");
        String id = support.inbound(owner, ChannelType.SIMULATED);

        support.assign(owner, id, luis.userId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(luis.userId()))
                .andExpect(jsonPath("$.assignedTo.name").value("Luis"));
        support.assign(owner, id, ana.userId()).andExpect(status().isOk());
        assertThat(assigneeOf(id)).isEqualTo(ana.userId());
        support.assign(owner, id, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").doesNotExist());
        assertThat(assigneeOf(id)).isNull();
    }

    @Test
    void theOwnerCannotAssignToAnInactiveAgentOrToSomeoneFromAnotherCompany() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        Session other = support.newAgent(support.newCompany(), "Ajeno");
        String id = support.inbound(owner, ChannelType.SIMULATED);
        support.unassign(id);
        agentService.deactivate(owner.tenantId(), ana.userId());

        support.assign(owner, id, ana.userId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_INACTIVE"));
        support.assign(owner, id, other.userId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
        assertThat(assigneeOf(id)).isNull();
    }

    // --- asignación: AGENT ---

    @Test
    void anAgentCanTakeAnUnassignedChatAndReleaseTheirOwn() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        String id = support.inbound(owner, ChannelType.SIMULATED);
        support.unassign(id);

        support.assign(ana, id, ana.userId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo.id").value(ana.userId()));
        support.assign(ana, id, ana.userId()).andExpect(status().isOk()); // ya es suyo: idempotente
        support.assign(ana, id, null).andExpect(status().isOk());
        assertThat(assigneeOf(id)).isNull();
    }

    @Test
    void anAgentGets403ForAnythingElse() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        Session luis = support.newAgent(owner, "Luis");
        String id = support.inbound(owner, ChannelType.SIMULATED);
        support.setAssignee(id, luis.userId());

        // Tomar un chat ajeno
        support.assign(ana, id, ana.userId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_FORBIDDEN"));
        // Quitarle el chat a otro
        support.assign(ana, id, null).andExpect(status().isForbidden());
        // Asignárselo a otro agente
        support.assign(ana, id, owner.userId()).andExpect(status().isForbidden());
        support.unassign(id);
        support.assign(ana, id, luis.userId()).andExpect(status().isForbidden());
        // Soltar un chat sin asignar
        support.assign(ana, id, null).andExpect(status().isForbidden());

        assertThat(assigneeOf(id)).isNull();
        // Una conversación de otra empresa no existe para ella
        Session otherOwner = support.newCompany();
        String foreign = support.inbound(otherOwner, ChannelType.SIMULATED);
        support.assign(ana, foreign, ana.userId()).andExpect(status().isNotFound());
    }

    @Test
    void twoAgentsTakingTheSameChatAtOnceOnlyOneWins() throws Exception {
        Session owner = support.newCompany();
        List<Session> agents = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            agents.add(support.newAgent(owner, "Agente " + i));
        }
        String id = support.inbound(owner, ChannelType.SIMULATED);
        support.unassign(id);

        CyclicBarrier start = new CyclicBarrier(agents.size());
        ExecutorService pool = Executors.newFixedThreadPool(agents.size());
        List<Future<Integer>> statuses = new ArrayList<>();
        try {
            for (Session agent : agents) {
                Callable<Integer> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return support.assign(agent, id, agent.userId()).andReturn().getResponse().getStatus();
                };
                statuses.add(pool.submit(task));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                codes.add(status.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).containsOnly(200, 403);
            assertThat(codes).filteredOn(code -> code == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(agents).extracting(Session::userId).contains(assigneeOf(id));
    }

    // --- asignación por turnos y desactivación ---

    @Test
    void newConversationsAreAssignedRoundRobinAmongActiveAgents() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        Session luis = support.newAgent(owner, "Luis");

        List<String> assignees = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            assignees.add(assigneeOf(support.inbound(owner, ChannelType.SIMULATED)));
        }
        assertThat(assignees).containsExactly(ana.userId(), luis.userId(), ana.userId(), luis.userId());

        agentService.deactivate(owner.tenantId(), luis.userId());
        assertThat(assigneeOf(support.inbound(owner, ChannelType.SIMULATED))).isEqualTo(ana.userId());
        assertThat(assigneeOf(support.inbound(owner, ChannelType.SIMULATED))).isEqualTo(ana.userId());
    }

    @Test
    void withoutActiveAgentsNewConversationsStayUnassigned() {
        Session owner = support.newCompany();
        assertThat(assigneeOf(support.inbound(owner, ChannelType.SIMULATED))).isNull();
    }

    @Test
    void deactivatingAnAgentUnassignsTheirOpenConversationsOnly() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        String open = support.inbound(owner, ChannelType.SIMULATED);
        String resolved = support.inbound(owner, ChannelType.FACEBOOK);
        support.setAssignee(open, ana.userId());
        support.setAssignee(resolved, ana.userId());
        support.changeStatus(owner, resolved, "RESOLVED").andExpect(status().isOk());

        agentService.deactivate(owner.tenantId(), ana.userId());

        assertThat(assigneeOf(open)).isNull();
        assertThat(assigneeOf(resolved)).isEqualTo(ana.userId()); // el historial conserva quién atendió
    }

    // --- estado y reapertura ---

    @Test
    void resolvingClosesAndReopeningWithoutAnotherOpenConversationWorks() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED);

        support.changeStatus(owner, id, "RESOLVED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.open").value(false));
        support.changeStatus(owner, id, "IN_PROGRESS").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.open").value(true));
    }

    @Test
    void reopeningWhenTheCustomerAlreadyHasAnotherOpenConversationIs409() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        String contact = "cliente-reabrir";
        String old = support.inbound(owner, ChannelType.WHATSAPP, contact, Instant.now());
        support.changeStatus(ana, old, "RESOLVED").andExpect(status().isOk());
        String current = support.inbound(owner, ChannelType.WHATSAPP, contact, Instant.now());
        assertThat(current).isNotEqualTo(old);

        support.changeStatus(ana, old, "IN_PROGRESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANOTHER_CONVERSATION_OPEN"))
                .andExpect(jsonPath("$.message").value("No se puede reabrir: este cliente ya tiene otra "
                        + "conversación abierta por WHATSAPP. Continúa la atención en esa conversación."));

        Conversation stillResolved = conversations.findById(old).orElseThrow();
        assertThat(stillResolved.getStatus()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(stillResolved.isOpen()).isFalse();
    }

    @Test
    void invalidStatusIsABadRequest() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED);

        support.changeStatus(owner, id, "ARCHIVED").andExpect(status().isBadRequest());
        support.putAs(owner, "/api/conversations/" + id + "/status", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.status").exists());
    }

    // --- respuestas rápidas ---

    @Test
    void everyoneUsesQuickRepliesButOnlyTheOwnerManagesThem() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");

        String created = support.postAs(owner, "/api/quick-replies", "{\"title\":\"Saludo\",\"text\":\"¡Hola!\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Saludo"))
                .andReturn().getResponse().getContentAsString();
        String replyId = JsonPath.read(created, "$.id");

        support.getAs(ana, "/api/quick-replies").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("¡Hola!"));
        support.postAs(ana, "/api/quick-replies", "{\"title\":\"X\",\"text\":\"Y\"}")
                .andExpect(status().isForbidden());
        support.putAs(ana, "/api/quick-replies/" + replyId, "{\"title\":\"X\",\"text\":\"Y\"}")
                .andExpect(status().isForbidden());
        support.deleteAs(ana, "/api/quick-replies/" + replyId).andExpect(status().isForbidden());

        support.putAs(owner, "/api/quick-replies/" + replyId, "{\"title\":\"Saludo\",\"text\":\"Buenas\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Buenas"));
        support.deleteAs(owner, "/api/quick-replies/" + replyId).andExpect(status().isNoContent());
        support.getAs(ana, "/api/quick-replies").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void quickRepliesAreIsolatedPerCompanyAndValidated() throws Exception {
        Session a = support.newCompany();
        Session b = support.newCompany();
        String created = support.postAs(a, "/api/quick-replies", "{\"title\":\"Horario\",\"text\":\"9 a 6\"}")
                .andReturn().getResponse().getContentAsString();
        String replyId = JsonPath.read(created, "$.id");

        support.getAs(b, "/api/quick-replies").andExpect(jsonPath("$.length()").value(0));
        support.putAs(b, "/api/quick-replies/" + replyId, "{\"title\":\"X\",\"text\":\"Y\"}")
                .andExpect(status().isNotFound());
        support.deleteAs(b, "/api/quick-replies/" + replyId).andExpect(status().isNotFound());
        support.postAs(a, "/api/quick-replies", "{\"title\":\"\",\"text\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.text").exists());
    }

    // --- bandeja y perfil ---

    @Test
    void theInboxFiltersByAssigneeStatusAndChannel() throws Exception {
        Session owner = support.newCompany();
        // Conversaciones creadas antes que el agente: la asignación por turnos no las toca
        String mine = support.inbound(owner, ChannelType.WHATSAPP);
        String unassigned = support.inbound(owner, ChannelType.FACEBOOK);
        String resolved = support.inbound(owner, ChannelType.SIMULATED);
        Session ana = support.newAgent(owner, "Ana");
        support.setAssignee(mine, ana.userId());
        support.changeStatus(owner, resolved, "RESOLVED").andExpect(status().isOk());
        support.inbound(support.newCompany(), ChannelType.WHATSAPP); // de otra empresa: nunca aparece

        support.getAs(ana, "/api/conversations").andExpect(jsonPath("$.totalItems").value(3));
        support.getAs(ana, "/api/conversations?assignee=me")
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(mine))
                .andExpect(jsonPath("$.items[0].contact.name").exists())
                .andExpect(jsonPath("$.items[0].replyWindowOpen").value(true))
                .andExpect(jsonPath("$.items[0].replyWindowExpiresAt").exists());
        support.getAs(ana, "/api/conversations?assignee=unassigned&status=NEW")
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(unassigned));
        support.getAs(ana, "/api/conversations?status=RESOLVED")
                .andExpect(jsonPath("$.items[0].id").value(resolved));
        support.getAs(ana, "/api/conversations?channel=FACEBOOK")
                .andExpect(jsonPath("$.totalItems").value(1));
        support.getAs(ana, "/api/conversations?size=2&page=1")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void theUnansweredFilterShowsOpenChatsWhoseLastCustomerMessageHasNoReplyAfterIt() throws Exception {
        Session owner = support.newCompany();
        String neverAnswered = support.inbound(owner, ChannelType.SIMULATED);
        String answered = support.inbound(owner, ChannelType.SIMULATED);
        support.reply(owner, answered, "listo").andExpect(status().isCreated());
        String customerWroteAgain = support.inbound(owner, ChannelType.SIMULATED, "insistente", Instant.now());
        support.reply(owner, customerWroteAgain, "te respondo").andExpect(status().isCreated());
        support.inbound(owner, ChannelType.SIMULATED, "insistente", Instant.now().plusSeconds(2));
        String resolvedWithoutReply = support.inbound(owner, ChannelType.SIMULATED);
        support.changeStatus(owner, resolvedWithoutReply, "RESOLVED").andExpect(status().isOk());
        String failedReply = support.inbound(owner, ChannelType.WHATSAPP); // el envío falla (localhost:9)
        support.reply(owner, failedReply, "no llega").andExpect(jsonPath("$.deliveryStatus").value("FAILED"));
        support.inbound(support.newCompany(), ChannelType.SIMULATED); // otra empresa

        String body = support.getAs(owner, "/api/conversations?unanswered=true&size=50")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.items[*].id");
        assertThat(ids).containsExactlyInAnyOrder(neverAnswered, customerWroteAgain, failedReply);
        support.getAs(owner, "/api/conversations?unanswered=true&channel=WHATSAPP")
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void aConversationCanBeMarkedAsSaleWithAValidAmount() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        String id = support.inbound(owner, ChannelType.SIMULATED);
        String url = "/api/conversations/" + id + "/sale";

        support.mockPatch(ana, url, "{\"isSale\":true,\"saleAmount\":150.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSale").value(true))
                .andExpect(jsonPath("$.saleAmount").value(150.5));
        org.bson.Document raw = mongoTemplate.getCollection("conversations")
                .find(new org.bson.Document("_id", id)).first();
        assertThat(raw).containsEntry("isSale", true);
        assertThat(raw.get("saleAmount")).isInstanceOf(org.bson.types.Decimal128.class);

        support.mockPatch(ana, url, "{\"isSale\":true,\"saleAmount\":0}").andExpect(status().isOk());
        support.mockPatch(ana, url, "{\"isSale\":false,\"saleAmount\":99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSale").value(false))
                .andExpect(jsonPath("$.saleAmount").doesNotExist());
    }

    @Test
    void saleValidationRequiresTheFlagAndANonNegativeAmountWhenItIsASale() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED);
        String url = "/api/conversations/" + id + "/sale";

        support.mockPatch(owner, url, "{\"isSale\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.saleAmount").value("El monto es obligatorio cuando hay venta"));
        support.mockPatch(owner, url, "{\"isSale\":true,\"saleAmount\":-1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.saleAmount").value("El monto no puede ser negativo"));
        support.mockPatch(owner, url, "{\"saleAmount\":10}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.isSale").exists());
        assertThat(conversations.findById(id).orElseThrow().isSale()).isFalse();
        support.mockPatch(support.newCompany(), url, "{\"isSale\":true,\"saleAmount\":5}")
                .andExpect(status().isNotFound());
    }

    @Test
    void assignableUsersAreTheActiveUsersOfTheCompanyIncludingTheOwner() throws Exception {
        Session owner = support.newCompany();
        Session ana = support.newAgent(owner, "Ana");
        Session luis = support.newAgent(owner, "Luis");
        support.newAgent(support.newCompany(), "Ajeno");
        agentService.deactivate(owner.tenantId(), luis.userId());

        String body = support.getAs(ana, "/api/users/assignable")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[*].id");
        List<String> roles = JsonPath.read(body, "$[*].role");
        assertThat(ids).containsExactlyInAnyOrder(owner.userId(), ana.userId());
        assertThat(roles).containsExactlyInAnyOrder("OWNER", "AGENT");
    }

    // --- "Cargar más": paginación por cursor ---

    /** Recorre la bandeja con "Cargar más" y devuelve los ids en el orden recibido. */
    private List<String> walkInbox(Session session, String filters, int size) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            String url = "/api/conversations?size=" + size + filters
                    + (cursor == null ? "" : "&before=" + java.net.URLEncoder.encode(cursor, "UTF-8"));
            String body = support.getAs(session, url).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            ids.addAll(JsonPath.<List<String>>read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
            if (cursor == null) {
                return ids;
            }
        }
        throw new AssertionError("la paginación no terminó");
    }

    @Test
    void loadMoreWalksTheWholeInboxInOrderWithoutGapsOrRepeatsEvenWithTiedTimes() throws Exception {
        Session owner = support.newCompany();
        Instant base = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            // Tres conversaciones comparten exactamente la misma hora: el desempate es el id
            Instant at = i < 3 ? base : base.plusSeconds(i);
            created.add(support.inbound(owner, ChannelType.SIMULATED, "cursor-" + i, at));
        }

        List<String> walked = walkInbox(owner, "", 3);

        assertThat(walked).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        // Mismo orden que la primera página "grande"
        String all = support.getAs(owner, "/api/conversations?size=50").andReturn().getResponse().getContentAsString();
        assertThat(walked).isEqualTo(JsonPath.<List<String>>read(all, "$.items[*].id"));
        support.getAs(owner, "/api/conversations?size=50").andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void aChatLeavingTheFilterBetweenPagesDoesNotMakeTheNextPageSkipAnother() throws Exception {
        Session owner = support.newCompany();
        Instant base = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            created.add(support.inbound(owner, ChannelType.SIMULATED, "gap-" + i, base.plusSeconds(i)));
        }
        String first = support.getAs(owner, "/api/conversations?status=NEW&size=3").andReturn().getResponse()
                .getContentAsString();
        List<String> firstPage = JsonPath.read(first, "$.items[*].id");
        String cursor = JsonPath.read(first, "$.nextCursor");

        // Un agente resuelve un chat ya cargado: sale del filtro "Nuevo" y todo se corre un puesto
        support.changeStatus(owner, firstPage.get(0), "RESOLVED").andExpect(status().isOk());

        String next = support.getAs(owner, "/api/conversations?status=NEW&size=3&before="
                + java.net.URLEncoder.encode(cursor, "UTF-8")).andReturn().getResponse().getContentAsString();
        List<String> secondPage = JsonPath.read(next, "$.items[*].id");
        List<String> union = new ArrayList<>(firstPage);
        union.addAll(secondPage);
        assertThat(union).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void theOpenFilterShowsOnlyNewAndInProgressOrOnlyResolved() throws Exception {
        Session owner = support.newCompany();
        String fresh = support.inbound(owner, ChannelType.SIMULATED);
        String working = support.inbound(owner, ChannelType.SIMULATED);
        support.changeStatus(owner, working, "IN_PROGRESS").andExpect(status().isOk());
        String done = support.inbound(owner, ChannelType.SIMULATED);
        support.changeStatus(owner, done, "RESOLVED").andExpect(status().isOk());

        String open = support.getAs(owner, "/api/conversations?open=true").andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(open, "$.items[*].id")).containsExactlyInAnyOrder(fresh, working);
        String resolved = support.getAs(owner, "/api/conversations?open=false").andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(resolved, "$.items[*].id")).containsExactly(done);
        support.getAs(owner, "/api/conversations").andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void anInvalidCursorIsAValidationError() throws Exception {
        Session owner = support.newCompany();
        for (String bad : new String[] {"abc", "123", "x_id", "_"}) {
            support.getAs(owner, "/api/conversations?before=" + bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.before").exists());
        }
    }

    @Test
    void markingAsReadResetsTheUnreadCounter() throws Exception {
        Session owner = support.newCompany();
        String id = support.inbound(owner, ChannelType.SIMULATED, "lector", Instant.now());
        support.inbound(owner, ChannelType.SIMULATED, "lector", Instant.now());
        assertThat(conversations.findById(id).orElseThrow().getUnreadCount()).isEqualTo(2);

        support.postAs(owner, "/api/conversations/" + id + "/read", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void theContactProfileListsPreviousConversationsAndKeepsNotes() throws Exception {
        Session owner = support.newCompany();
        String first = support.inbound(owner, ChannelType.SIMULATED, "historial", Instant.now());
        support.changeStatus(owner, first, "RESOLVED").andExpect(status().isOk());
        String second = support.inbound(owner, ChannelType.SIMULATED, "historial", Instant.now());
        String contactId = conversations.findById(second).orElseThrow().getContactId();

        support.mockPatch(owner, "/api/contacts/" + contactId, "{\"notes\":\"Prefiere WhatsApp\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Prefiere WhatsApp"))
                .andExpect(jsonPath("$.conversations.length()").value(2))
                .andExpect(jsonPath("$.conversations[0].id").value(second))
                .andExpect(jsonPath("$.conversations[1].id").value(first))
                .andExpect(jsonPath("$.conversations[1].open").value(false));
        support.getAs(support.newCompany(), "/api/contacts/" + contactId).andExpect(status().isNotFound());
    }
}
