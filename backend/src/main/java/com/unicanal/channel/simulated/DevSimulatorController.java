package com.unicanal.channel.simulated;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.DeliveryStatusUpdater;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.channel.InboundResult;
import com.unicanal.channel.StatusUpdate;
import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solo existe con el perfil {@code dev}: herramientas para probar la aplicación sin canales reales, siempre
 * dentro de la empresa del JWT. Fuera de dev todas estas rutas responden 404.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevSimulatorController {

    private final SimulatedChannelAdapter adapter;
    private final InboundMessageProcessor processor;
    private final MessageRepository messages;
    private final DeliveryStatusUpdater statusUpdater;
    private final Clock clock;

    public DevSimulatorController(SimulatedChannelAdapter adapter, InboundMessageProcessor processor,
            MessageRepository messages, DeliveryStatusUpdater statusUpdater, Clock clock) {
        this.adapter = adapter;
        this.processor = processor;
        this.messages = messages;
        this.statusUpdater = statusUpdater;
        this.clock = clock;
    }

    /** Permite a la interfaz saber si mostrar la página "Simulador" (404 fuera de dev). */
    @GetMapping("/status")
    public SimulatorStatus status(@AuthenticationPrincipal AuthPrincipal principal) {
        return new SimulatorStatus(true, adapter.pendingFailures(principal.tenantId()));
    }

    /** Un cliente escribe. */
    @PostMapping("/simulate-inbound")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulateInboundResponse simulateInbound(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SimulateInboundRequest request) {
        InboundResult result = processor.process(principal.tenantId(), adapter.toInbound(request));
        return new SimulateInboundResponse(result.contactId(), result.conversationId(), result.messageId(),
                result.newConversation(), result.duplicate());
    }

    /**
     * El canal informa un estado de entrega (entregado, leído, fallido) de un mensaje saliente, como haría el
     * webhook de Meta. Pasa por el mismo {@link DeliveryStatusUpdater}: los estados solo avanzan.
     */
    @PostMapping("/simulate-status")
    public SimulateStatusResponse simulateStatus(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SimulateStatusRequest request) {
        Message message = messages.findById(request.messageId())
                .filter(found -> principal.tenantId().equals(found.getTenantId()))
                .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Mensaje no encontrado"));
        if (message.getDirection() != Direction.OUTBOUND || message.getExternalId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_NOT_SENT",
                    "Solo se puede cambiar el estado de un mensaje enviado al cliente");
        }
        boolean applied = statusUpdater.apply(principal.tenantId(), new StatusUpdate(ChannelType.SIMULATED,
                SimulatedChannelAdapter.SIMULATED_ACCOUNT_ID, message.getExternalId(), request.status(),
                clock.instant()));
        return new SimulateStatusResponse(applied);
    }

    /**
     * Los próximos {@code count} envíos de la empresa fallarán (0 = ninguno), para probar "Reintentar". Con
     * {@code unconfirmed} el fallo simula que el canal no respondió (timeout).
     */
    @PostMapping("/simulate-send-failures")
    public SimulatorStatus simulateSendFailures(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SimulateSendFailuresRequest request) {
        adapter.failNextSends(principal.tenantId(), request.count(), Boolean.TRUE.equals(request.unconfirmed()));
        return new SimulatorStatus(true, adapter.pendingFailures(principal.tenantId()));
    }

    public record SimulateInboundResponse(String contactId, String conversationId, String messageId,
            boolean newConversation, boolean duplicate) {
    }

    public record SimulatorStatus(boolean enabled, int pendingSendFailures) {
    }

    public record SimulateStatusRequest(
            @NotBlank(message = "El mensaje es obligatorio") String messageId,
            @NotNull(message = "El estado es obligatorio") DeliveryStatus status) {
    }

    public record SimulateStatusResponse(boolean applied) {
    }

    public record SimulateSendFailuresRequest(
            @NotNull(message = "Indica cuántos envíos deben fallar")
            @Min(value = 0, message = "Entre 0 y 10") @Max(value = 10, message = "Entre 0 y 10") Integer count,
            // true: el canal "no responde" (envío sin confirmar) en vez de rechazar el envío
            Boolean unconfirmed) {
    }
}
