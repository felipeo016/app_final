package com.unicanal.channel.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.TestcontainersConfig;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.demo.DevDataSeeder;
import com.unicanal.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Sin el perfil dev no existe nada de desarrollo: ni las rutas /api/dev (ni para un usuario autenticado) ni el
 * cargador de datos demo, aunque SEED_DEMO esté activado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.seed-demo=true")
@Import(TestcontainersConfig.class)
class DevSimulatorDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository users;
    @Autowired
    private ApplicationContext context;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** Todas las rutas registradas en la aplicación. */
    static List<String> registeredPaths(RequestMappingHandlerMapping mappings) {
        return mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .toList();
    }

    @Test
    void noApiDevEndpointIsRegisteredOutsideTheDevProfile() {
        List<String> paths = registeredPaths(mappings);

        assertThat(paths).as("la aplicación sí tiene rutas").contains("/api/conversations");
        assertThat(paths).noneMatch(path -> path.startsWith("/api/dev"));
        assertThat(context.getBeansOfType(DevSimulatorController.class)).isEmpty();
    }

    @Test
    void theDevEndpointsAnswer404EvenToAnAuthenticatedUser() throws Exception {
        String token = authService.register(new RegisterRequest("Acme", "Ana",
                "nodev-" + UUID.randomUUID() + "@test.com", "s3cret-pass")).token();

        mockMvc.perform(post("/api/dev/simulate-inbound").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WHATSAPP\",\"contactExternalId\":\"5731\",\"text\":\"hola\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dev/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        for (String url : new String[] {"/api/dev/simulate-status", "/api/dev/simulate-send-failures"}) {
            mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void theDemoSeederDoesNotRunOutsideDevEvenWithSeedDemoTrue() {
        assertThat(context.getBeansOfType(DevDataSeeder.class)).isEmpty();
        assertThat(users.existsByEmail(DevDataSeeder.OWNER_EMAIL)).isFalse();
    }
}
