package com.unicanal.conversation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.LoginRequest;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.message.MessageType;
import com.unicanal.user.AgentService;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.dto.CreateAgentRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Empresas, agentes y conversaciones de prueba, y peticiones autenticadas a la API. */
final class ConversationTestSupport {

    static final String PASSWORD = "s3cret-pass";

    private final MockMvc mockMvc;
    private final AuthService authService;
    private final AgentService agentService;
    private final InboundMessageProcessor processor;
    private final MongoTemplate mongoTemplate;

    ConversationTestSupport(MockMvc mockMvc, AuthService authService, AgentService agentService,
            InboundMessageProcessor processor, MongoTemplate mongoTemplate) {
        this.mockMvc = mockMvc;
        this.authService = authService;
        this.agentService = agentService;
        this.processor = processor;
        this.mongoTemplate = mongoTemplate;
    }

    record Session(String token, String tenantId, String userId, Role role) {

        AuthPrincipal principal() {
            return new AuthPrincipal(userId, tenantId, role);
        }
    }

    Session newCompany() {
        AuthResponse auth = authService.register(new RegisterRequest("Empresa " + UUID.randomUUID(), "Dueña",
                "owner-" + UUID.randomUUID() + "@test.com", PASSWORD));
        return new Session(auth.token(), auth.tenant().id(), auth.user().id(), Role.OWNER);
    }

    Session newAgent(Session owner, String name) {
        String email = "agent-" + UUID.randomUUID() + "@test.com";
        User agent = agentService.create(owner.tenantId(), new CreateAgentRequest(name, email, PASSWORD));
        String token = authService.login(new LoginRequest(email, PASSWORD)).token();
        return new Session(token, owner.tenantId(), agent.getId(), Role.AGENT);
    }

    /** Un cliente escribe por el canal: crea (o reutiliza) contacto y conversación abierta. */
    String inbound(Session session, ChannelType channel, String contactExternalId, Instant timestamp) {
        return processor.process(session.tenantId(), new InboundMessage(channel, "cuenta-prueba", contactExternalId,
                "Cliente " + contactExternalId, "in-" + UUID.randomUUID(), MessageType.TEXT, "hola", timestamp))
                .conversationId();
    }

    String inbound(Session session, ChannelType channel) {
        return inbound(session, channel, "cli-" + UUID.randomUUID(), Instant.now());
    }

    void unassign(String conversationId) {
        mongoTemplate.updateFirst(ConversationService.byId(currentTenantOf(conversationId), conversationId),
                Update.update("assignedTo", null), Conversation.class);
    }

    void setAssignee(String conversationId, String userId) {
        mongoTemplate.updateFirst(ConversationService.byId(currentTenantOf(conversationId), conversationId),
                Update.update("assignedTo", userId), Conversation.class);
    }

    private String currentTenantOf(String conversationId) {
        return mongoTemplate.findById(conversationId, Conversation.class).getTenantId();
    }

    ResultActions getAs(Session session, String url) throws Exception {
        return mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()));
    }

    ResultActions postAs(Session session, String url, String json) throws Exception {
        return mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions putAs(Session session, String url, String json) throws Exception {
        return mockMvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions mockPatch(Session session, String url, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions deleteAs(Session session, String url) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token()));
    }

    ResultActions reply(Session session, String conversationId, String text) throws Exception {
        return postAs(session, "/api/conversations/" + conversationId + "/messages", "{\"text\":\"" + text + "\"}");
    }

    ResultActions assign(Session session, String conversationId, String agentId) throws Exception {
        String body = agentId == null ? "{\"agentId\":null}" : "{\"agentId\":\"" + agentId + "\"}";
        return putAs(session, "/api/conversations/" + conversationId + "/assignee", body);
    }

    ResultActions changeStatus(Session session, String conversationId, String status) throws Exception {
        return putAs(session, "/api/conversations/" + conversationId + "/status", "{\"status\":\"" + status + "\"}");
    }
}
