package com.unicanal.ai;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IA local con Ollama ({@code POST /api/chat}, sin streaming). Liviano a propósito: un solo cliente HTTP, sin
 * dependencias extra, y el modelo queda cargado con {@code keep_alive} para no volver a cargarlo en cada mensaje.
 *
 * <p>Cualquier fallo (Ollama cerrado, tiempo agotado, error del modelo) se convierte en
 * {@link AiUnavailableException}: quien llama decide seguir sin IA.
 */
@Component
public class OllamaProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    /** La comprobación de estado se reutiliza unos segundos: el encabezado de cada agente la consulta. */
    private static final Duration STATUS_CACHE = Duration.ofSeconds(5);
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final AiProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RestClient chatClient;
    private final RestClient pingClient;
    private volatile Instant statusCheckedAt = Instant.EPOCH;
    private volatile boolean lastStatus;

    public OllamaProvider(AiProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.chatClient = client(properties.baseUrl(), properties.timeout());
        this.pingClient = client(properties.baseUrl(), PING_TIMEOUT);
    }

    private static RestClient client(String baseUrl, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(PING_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public String chat(List<AiMessage> messages, Map<String, Object> jsonSchema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("keep_alive", properties.keepAlive());
        if (jsonSchema != null) {
            body.put("format", jsonSchema);
        }
        // Respuestas cortas y poco creativas: menos invención y menos tiempo de CPU/GPU
        body.put("options", Map.of("temperature", 0, "num_predict", 256));
        String raw;
        try {
            raw = chatClient.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(body)).retrieve().body(String.class);
        } catch (RestClientException e) {
            markUnavailable();
            throw new AiUnavailableException("Ollama no respondió: " + e.getMessage(), e);
        }
        try {
            JsonNode content = jsonMapper.readTree(raw == null ? "" : raw).path("message").path("content");
            if (!content.isString()) {
                throw new AiUnavailableException("Ollama respondió sin mensaje");
            }
            return content.asString();
        } catch (JacksonException e) {
            throw new AiUnavailableException("Respuesta de Ollama ilegible", e);
        }
    }

    @Override
    public boolean isAvailable() {
        Instant now = clock.instant();
        if (now.isBefore(statusCheckedAt.plus(STATUS_CACHE))) {
            return lastStatus;
        }
        boolean available;
        try {
            String raw = pingClient.get().uri("/api/tags").retrieve().body(String.class);
            available = hasModel(raw);
            if (!available) {
                log.warn("Ollama responde pero no tiene el modelo {}: ejecuta 'ollama pull {}'", model(), model());
            }
        } catch (RestClientException | JacksonException e) {
            available = false;
        }
        lastStatus = available;
        statusCheckedAt = now;
        return available;
    }

    private boolean hasModel(String rawTags) {
        JsonNode models = jsonMapper.readTree(rawTags == null ? "" : rawTags).path("models");
        for (JsonNode model : models) {
            String name = model.path("name").asString("");
            if (name.equals(properties.model()) || name.equals(properties.model() + ":latest")) {
                return true;
            }
        }
        return false;
    }

    private void markUnavailable() {
        lastStatus = false;
        statusCheckedAt = clock.instant();
    }

    /** Pedir un chat sin mensajes hace que Ollama solo cargue el modelo (y lo deje cargado según keep_alive). */
    @Override
    public void warmUp() {
        try {
            Map<String, Object> body = Map.of("model", properties.model(), "messages", List.of(),
                    "keep_alive", properties.keepAlive());
            chatClient.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(body)).retrieve().toBodilessEntity();
            log.info("IA: modelo {} precargado en Ollama ({})", properties.model(), properties.baseUrl());
        } catch (RuntimeException e) {
            log.warn("IA: no se pudo precargar el modelo {} en {} (¿Ollama está abierto?). La app sigue sin IA "
                    + "hasta que responda.", properties.model(), properties.baseUrl());
        }
    }
}
