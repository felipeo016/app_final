package com.unicanal.channel.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Construcción de payloads de WhatsApp y de su firma, para las pruebas. */
public final class WebhookTestSupport {

    public static final String PHONE_NUMBER_ID = "1234567890";
    public static final String APP_SECRET = "test-app-secret";

    private WebhookTestSupport() {
    }

    /** Valor de la cabecera X-Hub-Signature-256 para esos bytes. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String body) {
        return sign(APP_SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Payload con un solo mensaje de texto del número {@code from}. */
    public static String textMessage(String phoneNumberId, String from, String profileName, String messageId,
            String text) {
        return payload(phoneNumberId, contact(from, profileName),
                "{\"from\":\"" + from + "\",\"id\":\"" + messageId + "\",\"timestamp\":\"1730000000\","
                        + "\"type\":\"text\",\"text\":{\"body\":\"" + text + "\"}}", null);
    }

    /** Payload con un mensaje cualquiera (JSON del mensaje ya armado). */
    public static String withMessage(String phoneNumberId, String from, String profileName, String messageJson) {
        return payload(phoneNumberId, contact(from, profileName), messageJson, null);
    }

    /** Payload con un solo estado de entrega. */
    public static String statusOnly(String phoneNumberId, String messageId, String status) {
        return payload(phoneNumberId, null, null, "{\"id\":\"" + messageId + "\",\"status\":\"" + status
                + "\",\"timestamp\":\"1730000100\",\"recipient_id\":\"573001112222\"}");
    }

    private static String contact(String from, String profileName) {
        if (profileName == null) {
            return null;
        }
        return "{\"profile\":{\"name\":\"" + profileName + "\"},\"wa_id\":\"" + from + "\"}";
    }

    private static String payload(String phoneNumberId, String contactJson, String messageJson, String statusJson) {
        StringBuilder value = new StringBuilder("{\"messaging_product\":\"whatsapp\",\"metadata\":{"
                + "\"display_phone_number\":\"15550001111\",\"phone_number_id\":\"" + phoneNumberId + "\"}");
        if (contactJson != null) {
            value.append(",\"contacts\":[").append(contactJson).append(']');
        }
        if (messageJson != null) {
            value.append(",\"messages\":[").append(messageJson).append(']');
        }
        if (statusJson != null) {
            value.append(",\"statuses\":[").append(statusJson).append(']');
        }
        value.append('}');
        return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"waba-1\",\"changes\":["
                + "{\"field\":\"messages\",\"value\":" + value + "}]}]}";
    }
}
