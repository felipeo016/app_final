package com.unicanal.channel.whatsapp;

import com.unicanal.channel.ChannelAdapter;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.common.ApiException;
import com.unicanal.message.DeliveryStatus;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canal WhatsApp Cloud API: envío de texto, regla de la ventana de 24 h e interpretación del webhook. */
@Component
public class WhatsAppCloudAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCloudAdapter.class);

    private final WhatsAppProperties properties;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final WhatsAppPayloadParser parser;
    private final Clock clock;

    public WhatsAppCloudAdapter(WhatsAppProperties properties, RestClient whatsAppRestClient, JsonMapper jsonMapper,
            WhatsAppPayloadParser parser, Clock clock) {
        this.properties = properties;
        this.restClient = whatsAppRestClient;
        this.jsonMapper = jsonMapper;
        this.parser = parser;
        this.clock = clock;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WHATSAPP;
    }

    @Override
    public boolean isAvailable() {
        return properties.canSend();
    }

    @Override
    public WebhookPayload parseWebhook(byte[] rawBody) {
        return parser.parse(rawBody);
    }

    @Override
    public SendResult sendMessage(OutboundMessage message) {
        if (!isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CHANNEL_NOT_CONFIGURED",
                    "WhatsApp no está configurado");
        }
        if (!WindowPolicy.isOpen(message.lastInboundAt(), clock.instant())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WHATSAPP_WINDOW_CLOSED",
                    "Pasaron más de 24 horas desde el último mensaje del cliente: WhatsApp ya no permite "
                            + "mensajes libres, solo plantillas aprobadas");
        }

        String body = jsonMapper.writeValueAsString(Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", message.recipientExternalId(),
                "type", "text",
                "text", Map.of("preview_url", false, "body", message.text())));
        try {
            String response = restClient.post()
                    .uri("/{phoneNumberId}/messages", properties.phoneNumberId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return new SendResult(extractMessageId(response), DeliveryStatus.SENT);
        } catch (RestClientResponseException e) {
            log.error("WhatsApp rechazó el envío: HTTP {}", e.getStatusCode().value());
            throw sendFailed();
        } catch (RestClientException e) {
            log.error("No se pudo contactar a WhatsApp: {}", e.getMessage());
            throw sendFailed();
        }
    }

    private String extractMessageId(String response) {
        try {
            JsonNode id = jsonMapper.readTree(response).path("messages").path(0).path("id");
            if (id.isString() && !id.asString().isBlank()) {
                return id.asString();
            }
        } catch (JacksonException e) {
            // cae al error de abajo
        }
        log.error("Respuesta de WhatsApp sin id de mensaje");
        throw sendFailed();
    }

    private static ApiException sendFailed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CHANNEL_SEND_FAILED",
                "No se pudo enviar el mensaje por WhatsApp");
    }
}
