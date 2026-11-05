package com.unicanal.ai;

import com.unicanal.common.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Estado de la IA (para el encabezado) y "Sugerir respuesta". Cualquier usuario de la empresa. */
@RestController
public class AiController {

    private final AiAssistant assistant;

    public AiController(AiAssistant assistant) {
        this.assistant = assistant;
    }

    /** {@code state}: READY ("IA lista"), OFF ("IA apagada") o UNAVAILABLE ("IA no disponible"). */
    @GetMapping("/api/ai/status")
    public AiAssistant.Status status(@AuthenticationPrincipal AuthPrincipal principal) {
        return assistant.status(principal.tenantId());
    }

    /**
     * Propone una respuesta al último mensaje del cliente, sin enviarla. {@code canAnswer = false} si la información
     * del negocio no alcanza (el agente responde él mismo). 409 AI_OFF si la IA está apagada; 503 AI_UNAVAILABLE
     * si Ollama no responde.
     */
    @PostMapping("/api/conversations/{conversationId}/ai/suggest")
    public SuggestionResponse suggest(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String conversationId) {
        AiAnswer answer = assistant.suggest(principal.tenantId(), conversationId);
        return new SuggestionResponse(answer.canAnswer(), answer.text());
    }

    public record SuggestionResponse(boolean canAnswer, String text) {
    }
}
