package com.unicanal.ai;

import java.util.List;
import java.util.Map;

/**
 * Abstracción de un modelo de lenguaje (como {@code ChannelAdapter} para los canales). La implementación real es
 * {@link OllamaProvider}; las pruebas usan un proveedor falso, así que ninguna necesita Ollama corriendo.
 */
public interface AiProvider {

    /** Nombre del modelo, para mostrarlo en el estado. */
    String model();

    /**
     * Conversa con el modelo y devuelve el texto de su respuesta.
     *
     * @param jsonSchema si no es nulo, el modelo debe responder un JSON con ese esquema
     * @throws AiUnavailableException si el modelo no responde, tarda demasiado o devuelve un error
     */
    String chat(List<AiMessage> messages, Map<String, Object> jsonSchema);

    /** Comprobación rápida (pocos segundos como máximo): el servicio responde y tiene el modelo. */
    boolean isAvailable();

    /** Precarga el modelo en memoria. Nunca lanza excepciones. */
    default void warmUp() {
    }
}
