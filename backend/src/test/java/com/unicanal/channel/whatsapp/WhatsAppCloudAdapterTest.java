package com.unicanal.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.common.ApiException;
import com.unicanal.message.DeliveryStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class WhatsAppCloudAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final String BASE_URL = "https://graph.test/v23.0";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private record Fixture(WhatsAppCloudAdapter adapter, MockRestServiceServer server) {
    }

    private Fixture fixture(WhatsAppProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WhatsAppCloudAdapter adapter = new WhatsAppCloudAdapter(properties, builder.build(), jsonMapper,
                new WhatsAppPayloadParser(jsonMapper), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(adapter, server);
    }

    private Fixture configured() {
        return fixture(new WhatsAppProperties("v", "s", "tok-123", "555000", "tenant-1", BASE_URL));
    }

    private static OutboundMessage message(Instant lastInboundAt) {
        return new OutboundMessage("tenant-1", "conv-1", "573001112222", "Hola, ¿en qué te ayudo?", lastInboundAt);
    }

    @Test
    void sendsTextThroughTheCloudApiAndReturnsTheWamid() {
        Fixture f = configured();
        f.server().expect(requestTo(BASE_URL + "/555000/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messaging_product").value("whatsapp"))
                .andExpect(jsonPath("$.to").value("573001112222"))
                .andExpect(jsonPath("$.type").value("text"))
                .andExpect(jsonPath("$.text.body").value("Hola, ¿en qué te ayudo?"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.OUT1\"}]}", MediaType.APPLICATION_JSON));

        SendResult result = f.adapter().sendMessage(message(NOW.minus(Duration.ofHours(2))));

        assertThat(result.externalId()).isEqualTo("wamid.OUT1");
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        f.server().verify();
    }

    @Test
    void allowsSendingJustInsideTheWindow() {
        Fixture f = configured();
        f.server().expect(requestTo(BASE_URL + "/555000/messages"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.OUT2\"}]}", MediaType.APPLICATION_JSON));

        SendResult result = f.adapter().sendMessage(message(NOW.minus(Duration.ofHours(24)).plusSeconds(1)));

        assertThat(result.externalId()).isEqualTo("wamid.OUT2");
    }

    @Test
    void blocksFreeTextOutsideTheTwentyFourHourWindowWithoutCallingMeta() {
        Fixture f = configured(); // sin expectativas: cualquier llamada HTTP haría fallar la prueba

        assertThatThrownBy(() -> f.adapter().sendMessage(message(NOW.minus(Duration.ofHours(24)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("WHATSAPP_WINDOW_CLOSED");
                });
        f.server().verify();
    }

    @Test
    void blocksSendingWhenTheCustomerNeverWrote() {
        Fixture f = configured();

        assertThatThrownBy(() -> f.adapter().sendMessage(message(null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WHATSAPP_WINDOW_CLOSED"));
    }

    @Test
    void isUnavailableAndRefusesToSendWithoutCredentials() {
        Fixture f = fixture(new WhatsAppProperties("v", "s", "", "", "tenant-1", BASE_URL));

        assertThat(f.adapter().isAvailable()).isFalse();
        assertThatThrownBy(() -> f.adapter().sendMessage(message(NOW)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("CHANNEL_NOT_CONFIGURED");
                });
    }

    @Test
    void translatesMetaClientAndServerErrorsToBadGateway() {
        for (var response : new org.springframework.test.web.client.ResponseCreator[] {
                withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"message\":\"Invalid token\"}}")
                        .contentType(MediaType.APPLICATION_JSON),
                withServerError() }) {
            Fixture f = configured();
            f.server().expect(requestTo(BASE_URL + "/555000/messages")).andRespond(response);

            assertThatThrownBy(() -> f.adapter().sendMessage(message(NOW)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.getCode()).isEqualTo("CHANNEL_SEND_FAILED");
                    });
        }
    }

    @Test
    void failsWhenMetaRepliesWithoutAMessageId() {
        Fixture f = configured();
        f.server().expect(requestTo(BASE_URL + "/555000/messages"))
                .andRespond(withSuccess("{\"messages\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> f.adapter().sendMessage(message(NOW)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CHANNEL_SEND_FAILED"));
    }
}
