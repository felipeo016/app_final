package com.unicanal.channel;

/**
 * Abstracción de un canal de mensajería. Cada canal (WhatsApp, Instagram, Messenger, simulado)
 * traduce su protocolo a los tipos comunes de este paquete.
 */
public interface ChannelAdapter {

    ChannelType channelType();

    /** Envía un mensaje de texto al cliente a través del canal. */
    SendResult sendMessage(OutboundMessage message);

    /**
     * Interpreta el cuerpo crudo de un webhook y lo convierte en mensajes y estados de entrega.
     * No toca la base de datos ni conoce tenants: eso lo hace {@link WebhookService}.
     */
    WebhookPayload parseWebhook(byte[] rawBody);

    /** false si el canal no está configurado (p. ej. faltan credenciales). */
    default boolean isAvailable() {
        return true;
    }
}
