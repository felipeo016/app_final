package com.unicanal.channel.simulated;

import com.unicanal.channel.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un cliente "escribe" por el canal indicado. Es también el formato de {@code parseWebhook} del
 * canal simulado.
 *
 * @param contactExternalId teléfono o usuario del cliente en ese canal
 * @param messageId         opcional; fijarlo permite simular un reintento (idempotencia)
 */
public record SimulateInboundRequest(
        @NotNull(message = "El canal es obligatorio") ChannelType channel,
        @NotBlank(message = "El identificador del cliente es obligatorio")
        @Size(max = 100, message = "Máximo 100 caracteres") String contactExternalId,
        @Size(max = 100, message = "Máximo 100 caracteres") String contactName,
        @NotBlank(message = "El texto es obligatorio") @Size(max = 4096, message = "Máximo 4096 caracteres") String text,
        @Size(max = 100, message = "Máximo 100 caracteres") String messageId) {
}
