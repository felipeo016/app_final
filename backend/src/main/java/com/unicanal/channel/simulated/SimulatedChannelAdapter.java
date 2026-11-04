package com.unicanal.channel.simulated;

import com.unicanal.channel.ChannelAdapter;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InvalidWebhookPayloadException;
import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageType;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canal de desarrollo y demos: no habla con ningún servicio externo. "Envía" devolviendo un id falso y
 * "recibe" mensajes descritos con {@link SimulateInboundRequest}. Es el adaptador de respaldo del perfil
 * dev para los canales sin adaptador real.
 */
@Component
public class SimulatedChannelAdapter implements ChannelAdapter {

    /** Cuenta ficticia que "recibe" los mensajes simulados. */
    public static final String SIMULATED_ACCOUNT_ID = "simulated";

    private static final Logger log = LoggerFactory.getLogger(SimulatedChannelAdapter.class);

    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SimulatedChannelAdapter(JsonMapper jsonMapper, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.SIMULATED;
    }

    @Override
    public SendResult sendMessage(OutboundMessage message) {
        log.debug("[simulado] mensaje a {} en la conversación {}", message.recipientExternalId(),
                message.conversationId());
        return new SendResult("sim-out-" + UUID.randomUUID(), DeliveryStatus.SENT);
    }

    @Override
    public WebhookPayload parseWebhook(byte[] rawBody) {
        SimulateInboundRequest request;
        try {
            request = jsonMapper.readValue(rawBody, SimulateInboundRequest.class);
        } catch (JacksonException e) {
            throw new InvalidWebhookPayloadException();
        }
        return new WebhookPayload(List.of(toInbound(request)), List.of());
    }

    /** Convierte la petición del simulador en un mensaje entrante normalizado. */
    public InboundMessage toInbound(SimulateInboundRequest request) {
        String messageId = request.messageId() != null && !request.messageId().isBlank()
                ? request.messageId().trim() : "sim-in-" + UUID.randomUUID();
        return new InboundMessage(request.channel(), SIMULATED_ACCOUNT_ID, request.contactExternalId().trim(),
                request.contactName(), messageId, MessageType.TEXT, request.text(), clock.instant());
    }
}
