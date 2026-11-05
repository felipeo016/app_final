package com.unicanal.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Proveedor de IA de prueba: ninguna prueba necesita Ollama. Responde lo que se le programe (por separado la
 * redacción de "Sugerir respuesta" y la selección de "Respuesta automática"), puede fallar como Ollama caído o con
 * timeout, y guarda cada consulta para revisar qué se le envió al modelo.
 */
public class FakeAiProvider implements AiProvider {

    public record Request(List<AiMessage> messages, Map<String, Object> schema) {

        public String system() {
            return messages.get(0).content();
        }

        public String user() {
            return messages.get(messages.size() - 1).content();
        }

        /** Consulta de selección (modo automático), no de redacción. */
        public boolean selection() {
            return schema != null && schema.get("properties") instanceof Map<?, ?> properties
                    && properties.containsKey(AiPrompts.SELECTION_FIELD);
        }
    }

    public static final String CANNOT_ANSWER = "{\"puedeResponder\": false, \"respuesta\": \"\"}";
    public static final String NONE = "{\"respuestaRapidaId\": null}";

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, String> writer;
    private volatile Function<Request, String> selector;
    private volatile boolean available;

    public FakeAiProvider() {
        reset();
    }

    public static String answer(String text) {
        return "{\"puedeResponder\": true, \"respuesta\": \"" + text + "\"}";
    }

    public static String choose(String code) {
        return "{\"respuestaRapidaId\": \"" + code + "\"}";
    }

    public void reset() {
        requests.clear();
        writer = request -> answer("¡Hola! Con gusto te ayudo.");
        selector = request -> choose("R1");
        available = true;
    }

    /** Redacción libre ("Sugerir respuesta"). */
    public void respond(Function<Request, String> writer) {
        this.writer = writer;
    }

    public void respondAlways(String raw) {
        this.writer = request -> raw;
    }

    /** Selección de respuesta rápida ("Respuesta automática"). */
    public void select(Function<Request, String> selector) {
        this.selector = selector;
    }

    public void selectAlways(String raw) {
        this.selector = request -> raw;
    }

    /** Como Ollama cerrado: el estado dice "no disponible" y cada consulta falla. */
    public void down() {
        available = false;
        Function<Request, String> refused = request -> {
            throw new AiUnavailableException("Connection refused");
        };
        writer = refused;
        selector = refused;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public String model() {
        return "modelo-de-prueba";
    }

    @Override
    public String chat(List<AiMessage> messages, Map<String, Object> jsonSchema) {
        Request request = new Request(List.copyOf(messages), jsonSchema);
        requests.add(request);
        return request.selection() ? selector.apply(request) : writer.apply(request);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        FakeAiProvider fakeAiProvider() {
            return new FakeAiProvider();
        }
    }
}
