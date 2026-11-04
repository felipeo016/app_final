package com.unicanal.channel.simulated;

import static org.assertj.core.api.Assertions.assertThat;
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
