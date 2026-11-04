package com.unicanal.channel;

import java.time.Instant;

/**
 * Mensaje de texto a enviar.
 *
 * @param recipientExternalId teléfono o usuario del cliente en el canal
 * @param lastInboundAt       último mensaje del cliente; WhatsApp lo usa para la ventana de 24 h
 */
public record OutboundMessage(String tenantId, String conversationId, String recipientExternalId, String text,
        Instant lastInboundAt) {
}
