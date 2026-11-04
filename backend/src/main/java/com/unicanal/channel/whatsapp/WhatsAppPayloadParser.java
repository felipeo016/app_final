package com.unicanal.channel.whatsapp;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InvalidWebhookPayloadException;
import com.unicanal.channel.StatusUpdate;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traduce el JSON del webhook de WhatsApp Cloud API a {@link WebhookPayload}. Recorre TODOS los
 * {@code entry}, {@code changes}, {@code messages} y {@code statuses}. Los tipos de mensaje que aún no
 * se muestran (audio, video, sticker, ubicación, reacción...) se guardan como entrantes con un texto
 * descriptivo, para que ninguno se pierda ni haga fallar el webhook.
 */
@Component
public class WhatsAppPayloadParser {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppPayloadParser.class);

    private final JsonMapper jsonMapper;

    public WhatsAppPayloadParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public WebhookPayload parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new InvalidWebhookPayloadException();
        }
        if (root == null || !root.isObject()) {
            throw new InvalidWebhookPayloadException();
        }
        if (!"whatsapp_business_account".equals(text(root, "object"))) {
            log.debug("Webhook ignorado: objeto '{}' no es de WhatsApp", text(root, "object"));
            return WebhookPayload.empty();
        }

        List<InboundMessage> messages = new ArrayList<>();
        List<StatusUpdate> statuses = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                if (!"messages".equals(text(change, "field"))) {
                    continue;
                }
                parseValue(change.path("value"), messages, statuses);
            }
        }
        return new WebhookPayload(messages, statuses);
    }

    private void parseValue(JsonNode value, List<InboundMessage> messages, List<StatusUpdate> statuses) {
        String accountId = text(value.path("metadata"), "phone_number_id");

        Map<String, String> profileNames = new HashMap<>();
        for (JsonNode contact : value.path("contacts")) {
            String waId = text(contact, "wa_id");
            String name = text(contact.path("profile"), "name");
            if (waId != null && name != null && !name.isBlank()) {
                profileNames.put(waId, name.trim());
            }
        }

        for (JsonNode message : value.path("messages")) {
            String from = text(message, "from");
            if (from == null || from.isBlank()) {
                log.warn("Mensaje de WhatsApp sin remitente: se omite (id {})", text(message, "id"));
                continue;
            }
            String type = text(message, "type");
            messages.add(new InboundMessage(ChannelType.WHATSAPP, accountId, from, profileNames.get(from),
                    text(message, "id"), isImage(type) ? MessageType.IMAGE : MessageType.TEXT,
                    describe(type, message), timestamp(text(message, "timestamp"))));
        }

        for (JsonNode status : value.path("statuses")) {
            DeliveryStatus deliveryStatus = deliveryStatus(text(status, "status"));
            if (deliveryStatus == null) {
                continue;
            }
            statuses.add(new StatusUpdate(ChannelType.WHATSAPP, accountId, text(status, "id"), deliveryStatus,
                    timestamp(text(status, "timestamp"))));
        }
    }

    private static boolean isImage(String type) {
        return "image".equals(type);
    }

    /** Texto guardado para el mensaje: el contenido real si es texto, o una descripción del tipo. */
    private static String describe(String type, JsonNode message) {
        if (type == null) {
            return "[Mensaje recibido]";
        }
        return switch (type) {
            case "text" -> orDefault(text(message.path("text"), "body"), "");
            case "image" -> withDetail("[Imagen recibida]", text(message.path("image"), "caption"));
            case "audio" -> "[Audio recibido]";
            case "video" -> withDetail("[Video recibido]", text(message.path("video"), "caption"));
            case "sticker" -> "[Sticker recibido]";
            case "document" -> withDetail("[Documento recibido]", firstNonBlank(
                    text(message.path("document"), "filename"), text(message.path("document"), "caption")));
            case "location" -> withDetail("[Ubicación recibida]", text(message.path("location"), "name"));
            case "contacts" -> "[Contacto compartido]";
            case "reaction" -> withDetail("[Reacción recibida]", text(message.path("reaction"), "emoji"));
            case "button" -> orDefault(text(message.path("button"), "text"), "[Respuesta de botón recibida]");
            case "interactive" -> orDefault(firstNonBlank(
                    text(message.path("interactive").path("button_reply"), "title"),
                    text(message.path("interactive").path("list_reply"), "title")),
                    "[Respuesta interactiva recibida]");
            case "order" -> "[Pedido recibido]";
            case "system" -> "[Mensaje del sistema]";
            case "unsupported" -> "[Mensaje no soportado]";
            default -> "[Mensaje de tipo " + sanitizeType(type) + " recibido]";
        };
    }

    private static String withDetail(String label, String detail) {
        return detail == null || detail.isBlank() ? label : label + ": " + detail.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** El tipo viene de un tercero y termina en el texto guardado: se limita a letras/guion bajo y 30 caracteres. */
    private static String sanitizeType(String type) {
        String clean = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z_]", "");
        return clean.length() > 30 ? clean.substring(0, 30) : clean;
    }

    private static DeliveryStatus deliveryStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "sent" -> DeliveryStatus.SENT;
            case "delivered" -> DeliveryStatus.DELIVERED;
            case "read" -> DeliveryStatus.READ;
            case "failed" -> DeliveryStatus.FAILED;
            default -> null;
        };
    }

    /** Meta envía segundos desde epoch como texto. Si falta o es inválido, se usa la hora actual. */
    private static Instant timestamp(String epochSeconds) {
        if (epochSeconds != null) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(epochSeconds.trim()));
            } catch (NumberFormatException | java.time.DateTimeException e) {
                // cae al valor por defecto
            }
        }
        return Instant.now();
    }

    /** Valor escalar del campo como texto, o null si falta o no es un valor simple. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asString() : null;
    }
}
