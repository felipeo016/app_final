package com.unicanal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.unicanal.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Flujo completo de autenticación, roles y aislamiento entre empresas contra MongoDB real. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthFlowIntegrationTest {

    private static final String PASSWORD = "s3cret-pass";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void registerCreatesCompanyAndOwnerThatCanCallMe() throws Exception {
        String email = uniqueEmail("owner");

        String token = register("Acme", email);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("OWNER"))
                .andExpect(jsonPath("$.tenant.name").value("Acme"));
    }

    @Test
    void registerWithTakenEmailIgnoringCaseIsConflict() throws Exception {
        String email = uniqueEmail("dup");
        register("Acme", email);

        postJson("/api/auth/register", registerBody("Other", email.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registerWithInvalidDataReturnsFieldErrors() throws Exception {
        postJson("/api/auth/register", """
                {"companyName":"","ownerName":"Ana","email":"not-an-email","password":"short"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.companyName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void loginSucceedsWithRightPasswordAndFailsWithWrongOne() throws Exception {
        String email = uniqueEmail("login");
        register("Acme", email);

        postJson("/api/auth/login", loginBody(email, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        postJson("/api/auth/login", loginBody(email, "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedRoutesRejectMissingOrInvalidTokensWithJson401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer("garbage")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, bearer("garbage")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerManagesAgentsAndAgentCannot() throws Exception {
        String ownerToken = register("Acme", uniqueEmail("owner"));
        String agentEmail = uniqueEmail("agent");

        String agentId = createAgent(ownerToken, "Luis", agentEmail);

        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(agentId))
                .andExpect(jsonPath("$[0].role").value("AGENT"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        String agentToken = login(agentEmail);
        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, bearer(agentToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/agents").header(HttpHeaders.AUTHORIZATION, bearer(agentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(agentBody("Otro", uniqueEmail("x"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("AGENT"));
    }

    @Test
    void deactivatedAgentLosesAccessImmediatelyAndCannotLogIn() throws Exception {
        String ownerToken = register("Acme", uniqueEmail("owner"));
        String agentEmail = uniqueEmail("agent");
        String agentId = createAgent(ownerToken, "Luis", agentEmail);
        String agentToken = login(agentEmail);

        mockMvc.perform(patch("/api/agents/{id}/deactivate", agentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(agentToken)))
                .andExpect(status().isUnauthorized());
        postJson("/api/auth/login", loginBody(agentEmail, PASSWORD)).andExpect(status().isUnauthorized());
        // Idempotente
        mockMvc.perform(patch("/api/agents/{id}/deactivate", agentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCannotBeDeactivated() throws Exception {
        String ownerToken = register("Acme", uniqueEmail("owner"));
        String ownerId = JsonPath.read(
                mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                        .andReturn().getResponse().getContentAsString(), "$.user.id");

        mockMvc.perform(patch("/api/agents/{id}/deactivate", ownerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DEACTIVATE_OWNER"));
    }

    @Test
    void tenantsAreIsolatedFromEachOther() throws Exception {
        String ownerA = register("Empresa A", uniqueEmail("a"));
        String ownerB = register("Empresa B", uniqueEmail("b"));
        String agentOfA = createAgent(ownerA, "Agente de A", uniqueEmail("agent-a"));

        // B no ve a los agentes de A...
        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, bearer(ownerB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // ...ni puede desactivarlos (404, sin revelar que existen).
        mockMvc.perform(patch("/api/agents/{id}/deactivate", agentOfA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, bearer(ownerA)))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void emailHasAUniqueIndex() {
        boolean uniqueEmailIndex = mongoTemplate.indexOps("users").getIndexInfo().stream()
                .anyMatch(index -> index.isUnique()
                        && index.getIndexFields().stream().anyMatch(field -> field.getKey().equals("email")));

        assertThat(uniqueEmailIndex).isTrue();
    }

    // --- helpers ---

    private String register(String company, String email) throws Exception {
        String body = postJson("/api/auth/register", registerBody(company, email))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String login(String email) throws Exception {
        String body = postJson("/api/auth/login", loginBody(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String createAgent(String ownerToken, String name, String email) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/agents")
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(agentBody(name, email));
        String body = mockMvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String registerBody(String company, String email) {
        return """
                {"companyName":"%s","ownerName":"Ana","email":"%s","password":"%s"}""".formatted(company, email, PASSWORD);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private static String agentBody(String name, String email) {
        return """
                {"name":"%s","email":"%s","password":"%s"}""".formatted(name, email, PASSWORD);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.com";
    }
}
