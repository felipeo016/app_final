package com.unicanal.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link OllamaProvider} contra un servidor HTTP local que imita a Ollama (no hace falta Ollama real). */
class OllamaProviderTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(String path, int status, String body, Duration delay, AtomicReference<String> received)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> respond(exchange, status, body, delay, received));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body, Duration delay,
            AtomicReference<String> received) throws IOException {
        if (received != null) {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private OllamaProvider provider(String baseUrl, Duration timeout) {
        return new OllamaProvider(new AiProperties(baseUrl, "qwen2.5:3b", timeout, "24h", false, Duration.ZERO,
                Duration.ZERO), jsonMapper, Clock.systemUTC());
    }

    @Test
    void sendsTheChatWithJsonFormatAndKeepAliveAndReturnsTheContent() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        String url = start("/api/chat", 200, "{\"model\":\"qwen2.5:3b\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"{\\\"puedeResponder\\\":true,\\\"respuesta\\\":\\\"Hola\\\"}\"},\"done\":true}",
                Duration.ZERO, received);

        String content = provider(url, Duration.ofSeconds(5)).chat(
                List.of(AiMessage.system("reglas"), AiMessage.user("Cliente: hola")), AiPrompts.ANSWER_SCHEMA);

        assertThat(content).isEqualTo("{\"puedeResponder\":true,\"respuesta\":\"Hola\"}");
        JsonNode body = jsonMapper.readTree(received.get());
        assertThat(body.path("model").asString()).isEqualTo("qwen2.5:3b");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("keep_alive").asString()).isEqualTo("24h");
        assertThat(body.path("format").path("required").toString()).contains("puedeResponder", "respuesta");
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asString()).isEqualTo("Cliente: hola");
    }

    @Test
    void aSlowModelEndsInATimeout() throws IOException {
        String url = start("/api/chat", 200, "{\"message\":{\"content\":\"tarde\"}}", Duration.ofSeconds(3), null);

        assertThatThrownBy(() -> provider(url, Duration.ofMillis(300)).chat(List.of(AiMessage.user("hola")), null))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void ollamaClosedOrFailingIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OllamaProvider closed = provider("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2));
        assertThatThrownBy(() -> closed.chat(List.of(AiMessage.user("hola")), null))
                .isInstanceOf(AiUnavailableException.class);
        assertThat(closed.isAvailable()).isFalse();
        closed.warmUp(); // nunca lanza

        String url = start("/api/chat", 500, "{\"error\":\"model runner crashed\"}", Duration.ZERO, null);
        assertThatThrownBy(() -> provider(url, Duration.ofSeconds(2)).chat(List.of(AiMessage.user("hola")), null))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void isAvailableOnlyWhenTheModelIsInstalled() throws IOException {
        String url = start("/api/tags", 200, "{\"models\":[{\"name\":\"qwen2.5:1.5b\"},{\"name\":\"qwen2.5:3b\"}]}",
                Duration.ZERO, null);
        assertThat(provider(url, Duration.ofSeconds(2)).isAvailable()).isTrue();
        stop();

        String other = start("/api/tags", 200, "{\"models\":[{\"name\":\"llama3:8b\"}]}", Duration.ZERO, null);
        assertThat(provider(other, Duration.ofSeconds(2)).isAvailable()).as("falta 'ollama pull'").isFalse();
    }
}
