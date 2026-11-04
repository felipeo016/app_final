package com.unicanal.channel.simulated;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.RegisterRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Sin el perfil dev el simulador no existe: ni siquiera un usuario autenticado puede usarlo. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DevSimulatorDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;

    @Test
    void theSimulateEndpointDoesNotExistOutsideTheDevProfile() throws Exception {
        String token = authService.register(new RegisterRequest("Acme", "Ana",
                "nodev-" + UUID.randomUUID() + "@test.com", "s3cret-pass")).token();

        mockMvc.perform(post("/api/dev/simulate-inbound").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WHATSAPP\",\"contactExternalId\":\"5731\",\"text\":\"hola\"}"))
                .andExpect(status().isNotFound());
    }
}
