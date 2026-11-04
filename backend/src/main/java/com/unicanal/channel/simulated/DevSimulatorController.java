package com.unicanal.channel.simulated;

import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.channel.InboundResult;
import com.unicanal.common.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Solo existe con el perfil {@code dev}. Simula que un cliente escribe, en la empresa del JWT. */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevSimulatorController {

    private final SimulatedChannelAdapter adapter;
    private final InboundMessageProcessor processor;

    public DevSimulatorController(SimulatedChannelAdapter adapter, InboundMessageProcessor processor) {
        this.adapter = adapter;
        this.processor = processor;
    }

    @PostMapping("/simulate-inbound")
    @ResponseStatus(HttpStatus.CREATED)
    public SimulateInboundResponse simulateInbound(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SimulateInboundRequest request) {
        InboundResult result = processor.process(principal.tenantId(), adapter.toInbound(request));
        return new SimulateInboundResponse(result.contactId(), result.conversationId(), result.messageId(),
                result.newConversation(), result.duplicate());
    }

    public record SimulateInboundResponse(String contactId, String conversationId, String messageId,
            boolean newConversation, boolean duplicate) {
    }
}
