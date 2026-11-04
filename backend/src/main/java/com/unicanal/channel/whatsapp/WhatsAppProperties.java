package com.unicanal.channel.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciales y ajustes de la Cloud API de Meta. Siempre vienen del entorno (nunca del código).
 * Un solo número de WhatsApp por instalación; {@code defaultTenantId} es la empresa dueña de ese número.
 */
@ConfigurationProperties("app.whatsapp")
public record WhatsAppProperties(String verifyToken, String appSecret, String accessToken, String phoneNumberId,
        String defaultTenantId, String graphApiBaseUrl) {

    public WhatsAppProperties {
        verifyToken = blankIfNull(verifyToken);
        appSecret = blankIfNull(appSecret);
        accessToken = blankIfNull(accessToken);
        phoneNumberId = blankIfNull(phoneNumberId);
        defaultTenantId = blankIfNull(defaultTenantId);
        graphApiBaseUrl = graphApiBaseUrl == null || graphApiBaseUrl.isBlank()
                ? "https://graph.facebook.com/v23.0" : graphApiBaseUrl;
    }

    /** Hay credenciales para enviar mensajes. */
    public boolean canSend() {
        return !accessToken.isBlank() && !phoneNumberId.isBlank();
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    /** Evita que los secretos terminen en un log. */
    @Override
    public String toString() {
        return "WhatsAppProperties[phoneNumberId=" + phoneNumberId + ", defaultTenantId=" + defaultTenantId
                + ", graphApiBaseUrl=" + graphApiBaseUrl + ", secrets=***]";
    }
}
