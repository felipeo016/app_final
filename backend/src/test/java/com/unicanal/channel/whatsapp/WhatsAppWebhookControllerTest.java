package com.unicanal.channel.whatsapp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.channel.WebhookService;
import com.unicanal.channel.WebhookService.WebhookOutcome;
import com.unicanal.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Traducción del resultado del webhook a código HTTP: 500 solo ante errores pasajeros. */
class WhatsAppWebhookControllerTest {

    private static final String BODY = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}";

    private WebhookService webhookService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WhatsAppProperties properties = new WhatsAppProperties("verify", WebhookTestSupport.APP_SECRET, "", "", "",
                null);
        webhookService = mock(WebhookService.class);
        WhatsAppWebhookController controller = new WhatsAppWebhookController(properties,
                new SignatureVerifier(properties), mock(WhatsAppCloudAdapter.class), webhookService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private ResultActions signedPost() throws Exception {
        return mockMvc.perform(post("/api/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Hub-Signature-256", WebhookTestSupport.sign(BODY)));
    }

    @Test
    void permanentErrorsOnlyStillAnswer200() throws Exception {
        when(webhookService.handle(any(), any())).thenReturn(new WebhookOutcome(1, 0, 0, 0, 2, 0));

        signedPost().andExpect(status().isOk());
    }

    @Test
    void anyTransientErrorAnswers500SoMetaRetries() throws Exception {
        when(webhookService.handle(any(), any())).thenReturn(new WebhookOutcome(3, 0, 0, 0, 1, 1));

        signedPost().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("WEBHOOK_PROCESSING_FAILED"));
    }
}
