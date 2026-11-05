package com.unicanal.channel.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelType;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfig.class)
class DevSimulatorIntegrationTest {

    private static final String URL = "/api/dev/simulate-inbound";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private ContactRepository contacts;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;

    private record Session(String token, String tenantId) {
    }

    private Session newCompany() {
        var auth = authService.register(new RegisterRequest("Empresa " + UUID.randomUUID(), "Ana",
                "sim-" + UUID.randomUUID() + "@test.com", "s3cret-pass"));
        return new Session(auth.token(), auth.tenant().id());
    }

    private ResultActions simulate(Session session, String body) throws Exception {
        return mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String channel, String contact, String name, String text, String messageId) {
        return "{\"channel\":\"" + channel + "\",\"contactExternalId\":\"" + contact + "\","
                + (name != null ? "\"contactName\":\"" + name + "\"," : "")
                + (messageId != null ? "\"messageId\":\"" + messageId + "\"," : "")
                + "\"text\":\"" + text + "\"}";
    }

    @Test
    void createsContactAndConversationOnTheFirstMessageAndReusesThemAfterwards() throws Exception {
        Session session = newCompany();

        String first = simulate(session, body("WHATSAPP", "573009998888", "Ana", "hola", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newConversation").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        String second = simulate(session, body("WHATSAPP", "573009998888", "Ana", "sigo aquí", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newConversation").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.conversationId"))
                .isEqualTo(JsonPath.read(first, "$.conversationId"));
        Contact contact = contacts.findByTenantIdAndIdentityKey(session.tenantId(), "WHATSAPP:573009998888")
                .orElseThrow();
        assertThat(contact.getName()).isEqualTo("Ana");
        Conversation conversation = conversations.findByIdAndTenantId(JsonPath.read(first, "$.conversationId"),
                session.tenantId()).orElseThrow();
        assertThat(conversation.getUnreadCount()).isEqualTo(2);
        assertThat(conversation.getLastMessagePreview()).isEqualTo("sigo aquí");
    }

    @Test
    void worksForEveryChannel() throws Exception {
        Session session = newCompany();

        for (ChannelType channel : ChannelType.values()) {
            simulate(session, body(channel.name(), "cliente-1", "Luis", "hola por " + channel, null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.newConversation").value(true));
        }

        // Un contacto distinto por canal (identidad distinta) y una conversación abierta por cada uno
        for (ChannelType channel : ChannelType.values()) {
            Contact byChannel = contacts.findByTenantIdAndIdentityKey(session.tenantId(),
                    channel + ":cliente-1").orElseThrow();
            assertThat(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(session.tenantId(),
                    byChannel.getId(), channel)).isPresent();
        }
    }

    @Test
    void aFixedMessageIdSimulatesARetryWithoutDuplicating() throws Exception {
        Session session = newCompany();
        String request = body("INSTAGRAM", "ana_ig", "Ana", "hola", "retry-" + UUID.randomUUID());

        simulate(session, request).andExpect(status().isCreated()).andExpect(jsonPath("$.duplicate").value(false));
        simulate(session, request).andExpect(status().isCreated()).andExpect(jsonPath("$.duplicate").value(true));

        Contact contact = contacts.findByTenantIdAndIdentityKey(session.tenantId(), "INSTAGRAM:ana_ig").orElseThrow();
        Conversation conversation = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(session.tenantId(),
                contact.getId()).get(0);
        assertThat(conversation.getUnreadCount()).isEqualTo(1);
    }

    @Test
    void aResolvedConversationStartsANewOneOnTheNextMessage() throws Exception {
        Session session = newCompany();
        simulate(session, body("FACEBOOK", "fb-1", "Marta", "primero", null)).andExpect(status().isCreated());
        Contact contact = contacts.findByTenantIdAndIdentityKey(session.tenantId(), "FACEBOOK:fb-1").orElseThrow();
        Conversation first = conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(session.tenantId(),
                contact.getId(), ChannelType.FACEBOOK).orElseThrow();
        first.setStatus(ConversationStatus.RESOLVED);
        conversations.save(first);

        simulate(session, body("FACEBOOK", "fb-1", "Marta", "segundo", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.newConversation").value(true));

        List<Conversation> all = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(session.tenantId(),
                contact.getId());
        assertThat(all).hasSize(2);
        assertThat(all.get(0).isOpen()).isTrue();
        assertThat(all.get(1).isOpen()).isFalse();
    }

    @Test
    void theTenantComesFromTheJwtSoCompaniesNeverShareContacts() throws Exception {
        Session a = newCompany();
        Session b = newCompany();

        simulate(a, body("WHATSAPP", "573001230000", "Ana de A", "hola A", null)).andExpect(status().isCreated());
        simulate(b, body("WHATSAPP", "573001230000", "Ana de B", "hola B", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.newConversation").value(true));

        assertThat(contacts.findByTenantIdAndIdentityKey(a.tenantId(), "WHATSAPP:573001230000").orElseThrow()
                .getName()).isEqualTo("Ana de A");
        assertThat(contacts.findByTenantIdAndIdentityKey(b.tenantId(), "WHATSAPP:573001230000").orElseThrow()
                .getName()).isEqualTo("Ana de B");
        Message messageOfA = messages.findAll().stream().filter(m -> m.getContent().equals("hola A")).findFirst()
                .orElseThrow();
        assertThat(messageOfA.getTenantId()).isEqualTo(a.tenantId());
    }

    // --- herramientas del simulador (paso 8b) ---

    private ResultActions postAs(Session session, String url, String body) throws Exception {
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String replyAndGetMessageId(Session session, String conversationId, String text) throws Exception {
        String body = postAs(session, "/api/conversations/" + conversationId + "/messages",
                "{\"text\":\"" + text + "\"}").andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void theStatusEndpointTellsTheUiThatTheSimulatorExists() throws Exception {
        Session session = newCompany();
        mockMvc.perform(get("/api/dev/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.pendingSendFailures").value(0));
    }

    @Test
    void minutesAgoDatesTheMessageInThePastSoTheWhatsappWindowCanBeClosed() throws Exception {
        Session session = newCompany();
        String body = simulate(session, "{\"channel\":\"WHATSAPP\",\"contactExternalId\":\"573000000001\","
                + "\"text\":\"de ayer\",\"minutesAgo\":1500}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(body, "$.conversationId");

        Conversation conversation = conversations.findByIdAndTenantId(conversationId, session.tenantId()).orElseThrow();
        assertThat(conversation.getLastInboundAt()).isBefore(java.time.Instant.now().minusSeconds(24 * 3600));
        postAs(session, "/api/conversations/" + conversationId + "/messages", "{\"text\":\"hola\"}")
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("WHATSAPP_WINDOW_CLOSED"));
        simulate(session, "{\"channel\":\"WHATSAPP\",\"contactExternalId\":\"5730\",\"text\":\"x\",\"minutesAgo\":-1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.minutesAgo").exists());
    }

    @Test
    void deliveryStatusesCanBeSimulatedAndOnlyMoveForward() throws Exception {
        Session session = newCompany();
        String conversationId = JsonPath.read(simulate(session, body("SIMULATED", "cli-st", "Ana", "hola", null))
                .andReturn().getResponse().getContentAsString(), "$.conversationId");
        String messageId = replyAndGetMessageId(session, conversationId, "respuesta");

        postAs(session, "/api/dev/simulate-status", "{\"messageId\":\"" + messageId + "\",\"status\":\"READ\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        postAs(session, "/api/dev/simulate-status", "{\"messageId\":\"" + messageId + "\",\"status\":\"DELIVERED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false));
        assertThat(messages.findById(messageId).orElseThrow().getDeliveryStatus()).isEqualTo(DeliveryStatus.READ);

        // Otra empresa no puede tocar el mensaje
        postAs(newCompany(), "/api/dev/simulate-status", "{\"messageId\":\"" + messageId + "\",\"status\":\"READ\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void aFailedStatusFromTheChannelCarriesAReasonForTheAgent() throws Exception {
        Session session = newCompany();
        String conversationId = JsonPath.read(simulate(session, body("SIMULATED", "cli-fail", "Ana", "hola", null))
                .andReturn().getResponse().getContentAsString(), "$.conversationId");
        String messageId = replyAndGetMessageId(session, conversationId, "respuesta");

        postAs(session, "/api/dev/simulate-status", "{\"messageId\":\"" + messageId + "\",\"status\":\"FAILED\"}")
                .andExpect(jsonPath("$.applied").value(true));

        Message failed = messages.findById(messageId).orElseThrow();
        assertThat(failed.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("El canal informó que no pudo entregar el mensaje");
    }

    @Test
    void simulatedSendFailuresLetTheRetryButtonBeTested() throws Exception {
        Session session = newCompany();
        Session other = newCompany();
        String conversationId = JsonPath.read(simulate(session, body("SIMULATED", "cli-retry", "Ana", "hola", null))
                .andReturn().getResponse().getContentAsString(), "$.conversationId");
        String otherConversation = JsonPath.read(simulate(other, body("SIMULATED", "cli-x", "Luis", "hola", null))
                .andReturn().getResponse().getContentAsString(), "$.conversationId");

        postAs(session, "/api/dev/simulate-send-failures", "{\"count\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingSendFailures").value(1));

        // Solo falla el próximo envío de ESA empresa
        postAs(other, "/api/conversations/" + otherConversation + "/messages", "{\"text\":\"ok\"}")
                .andExpect(jsonPath("$.deliveryStatus").value("SENT"));
        String failed = postAs(session, "/api/conversations/" + conversationId + "/messages", "{\"text\":\"uno\"}")
                .andExpect(jsonPath("$.deliveryStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("El canal simulado rechazó el envío (fallo simulado)"))
                .andReturn().getResponse().getContentAsString();
        String messageId = JsonPath.read(failed, "$.id");

        postAs(session, "/api/conversations/" + conversationId + "/messages/" + messageId + "/retry", "")
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryStatus").value("SENT"));
        postAs(session, "/api/dev/simulate-send-failures", "{\"count\":11}").andExpect(status().isBadRequest());

        // Modo "sin confirmación": simula que el canal no respondió
        postAs(session, "/api/dev/simulate-send-failures", "{\"count\":1,\"unconfirmed\":true}")
                .andExpect(status().isOk());
        postAs(session, "/api/conversations/" + conversationId + "/messages", "{\"text\":\"dos\"}")
                .andExpect(jsonPath("$.deliveryStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CHANNEL_SEND_UNCONFIRMED"))
                .andExpect(jsonPath("$.failureReason")
                        .value("No se pudo confirmar el envío; puede que el cliente sí lo haya recibido"));
    }

    @Test
    void requiresAuthenticationAndValidData() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("WHATSAPP", "5731", null, "hola", null)))
                .andExpect(status().isUnauthorized());

        Session session = newCompany();
        simulate(session, "{\"channel\":\"WHATSAPP\",\"contactExternalId\":\"\",\"text\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.contactExternalId").exists())
                .andExpect(jsonPath("$.fieldErrors.text").exists());
        simulate(session, "{\"contactExternalId\":\"5731\",\"text\":\"hola\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.channel").exists());
        simulate(session, "{\"channel\":\"TELEGRAM\",\"contactExternalId\":\"5731\",\"text\":\"hola\"}")
                .andExpect(status().isBadRequest());
    }
}
