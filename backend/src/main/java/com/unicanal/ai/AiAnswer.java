package com.unicanal.ai;

import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Respuesta del modelo: {@code {"puedeResponder": bool, "respuesta": string}}. Cualquier cosa distinta (JSON
 * inválido, campos que faltan o de otro tipo, "true" sin texto, un texto demasiado largo) se trata como "no puede
 * responder": el chat queda para un humano.
 */
public record AiAnswer(boolean canAnswer, String text) {

    /** Una respuesta de más de esto no respetó "máximo 3 frases": no se envía. */
    static final int MAX_LENGTH = 600;

    public static final AiAnswer CANNOT = new AiAnswer(false, null);

    /** Vacío si la salida no es un JSON válido con el formato pedido. */
    public static Optional<AiAnswer> parse(String raw, JsonMapper jsonMapper) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = jsonMapper.readTree(raw.strip());
        } catch (JacksonException e) {
            return Optional.empty();
        }
        JsonNode can = node.path("puedeResponder");
        JsonNode text = node.path("respuesta");
        if (!node.isObject() || !can.isBoolean() || !(text.isString() || text.isNull() || text.isMissingNode())) {
            return Optional.empty();
        }
        if (!can.asBoolean()) {
            return Optional.of(CANNOT);
        }
        String answer = text.isString() ? text.asString().strip() : "";
        if (answer.isEmpty() || answer.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new AiAnswer(true, answer));
    }
}
