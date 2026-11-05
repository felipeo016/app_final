package com.unicanal.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IA local con Ollama. Todo se configura por variables de entorno (ver application.yml).
 *
 * @param baseUrl        dónde escucha Ollama (OLLAMA_URL)
 * @param model          modelo a usar (OLLAMA_MODEL), p. ej. qwen2.5:3b
 * @param timeout        tiempo máximo de una respuesta del modelo (OLLAMA_TIMEOUT_SECONDS)
 * @param keepAlive      cuánto tiempo deja Ollama el modelo cargado tras cada uso (OLLAMA_KEEP_ALIVE)
 * @param warmUp         precargar el modelo al arrancar el backend, para que la primera respuesta no tarde
 * @param typingDelayMin espera mínima antes de enviar una respuesta automática ("La IA está escribiendo…")
 * @param typingDelayMax espera máxima
 */
@ConfigurationProperties("app.ai")
public record AiProperties(
        @DefaultValue("http://localhost:11434") String baseUrl,
        @DefaultValue("qwen2.5:3b") String model,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("24h") String keepAlive,
        @DefaultValue("true") boolean warmUp,
        @DefaultValue("2s") Duration typingDelayMin,
        @DefaultValue("4s") Duration typingDelayMax) {
}
