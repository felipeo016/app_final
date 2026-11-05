package com.unicanal.channel.simulated;

import com.unicanal.channel.ChannelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un cliente "escribe" por el canal indicado. Es también el formato de {@code parseWebhook} del
 * canal simulado.
 *
 * @param contactExternalId teléfono o usuario del cliente en ese canal
 * @param messageId         opcional; fijarlo permite simular un reintento (idempotencia)
 * @param minutesAgo        opcional; fecha el mensaje en el pasado (p. ej. 1500 para cerrar la ventana de
 *                          24 h de WhatsApp, o 20 para que la alerta de riesgo lo marque en la siguiente pasada)
 */
public record SimulateInboundRequest(
        @NotNull(message = "El canal es obligatorio") ChannelType channel,
        @NotBlank(message = "El identificador del cliente es obligatorio")
        @Size(max = 100, message = "Máximo 100 caracteres") String contactExternalId,
        @Size(max = 100, message = "Máximo 100 caracteres") String contactName,
        @NotBlank(message = "El texto es obligatorio") @Size(max = 4096, message = "Máximo 4096 caracteres") String text,
        @Size(max = 100, message = "Máximo 100 caracteres") String messageId,
        @Min(value = 0, message = "No puede ser negativo")
        @Max(value = 10080, message = "Máximo 7 días (10080 minutos)") Integer minutesAgo) {

    /** Constructor sin {@code minutesAgo}: el mensaje es de ahora. */
    public SimulateInboundRequest(ChannelType channel, String contactExternalId, String contactName, String text,
            String messageId) {
        this(channel, contactExternalId, contactName, text, messageId, null);
    }
}
