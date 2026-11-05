package com.unicanal.channel.simulated;

import com.unicanal.channel.ChannelAdapter;
import com.unicanal.channel.ChannelSendErrors;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InvalidWebhookPayloadException;
import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageType;
import com.unicanal.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
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
    /** Fallos simulados pendientes por empresa (solo los activa el simulador del perfil dev). */
    private final Map<String, FailurePlan> pendingFailures = new ConcurrentHashMap<>();

    /** {@code unconfirmed}: simula que el canal no respondió (timeout) en vez de rechazar el envío. */
    private record FailurePlan(int remaining, boolean unconfirmed) {
    }

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
        FailurePlan failure = takeFailure(message.tenantId());
        if (failure != null) {
            log.debug("[simulado] fallo simulado del envío en la conversación {}", message.conversationId());
            if (failure.unconfirmed()) {
                throw ChannelSendErrors.unconfirmed();
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, ChannelSendErrors.FAILED,
                    "El canal simulado rechazó el envío (fallo simulado)");
        }
        log.debug("[simulado] mensaje a {} en la conversación {}", message.recipientExternalId(),
                message.conversationId());
        return new SendResult("sim-out-" + UUID.randomUUID(), DeliveryStatus.SENT);
    }

    /**
     * Solo para el simulador (perfil dev): los próximos {@code count} envíos de la empresa fallan, para poder
     * probar el estado FAILED y el botón "Reintentar".
     */
    public void failNextSends(String tenantId, int count, boolean unconfirmed) {
        if (count <= 0) {
            pendingFailures.remove(tenantId);
        } else {
            pendingFailures.put(tenantId, new FailurePlan(count, unconfirmed));
        }
    }

    public int pendingFailures(String tenantId) {
        FailurePlan plan = pendingFailures.get(tenantId);
        return plan == null ? 0 : plan.remaining();
    }

    /** Consume un fallo pendiente de forma atómica. Devuelve el fallo a simular, o null si este envío sale bien. */
    private FailurePlan takeFailure(String tenantId) {
        FailurePlan[] taken = {null};
        pendingFailures.computeIfPresent(tenantId, (tenant, plan) -> {
            taken[0] = plan;
            return plan.remaining() > 1 ? new FailurePlan(plan.remaining() - 1, plan.unconfirmed()) : null;
        });
        return taken[0];
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
        Instant timestamp = clock.instant();
        if (request.minutesAgo() != null && request.minutesAgo() > 0) {
            timestamp = timestamp.minus(Duration.ofMinutes(request.minutesAgo()));
        }
        return new InboundMessage(request.channel(), SIMULATED_ACCOUNT_ID, request.contactExternalId().trim(),
                request.contactName(), messageId, MessageType.TEXT, request.text(), timestamp);
    }
}
