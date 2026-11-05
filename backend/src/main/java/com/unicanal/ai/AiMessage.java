package com.unicanal.ai;

/** Un mensaje para el modelo: {@code role} es "system" o "user" (como en la API de chat de Ollama). */
public record AiMessage(String role, String content) {

    public static AiMessage system(String content) {
        return new AiMessage("system", content);
    }

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }
}
