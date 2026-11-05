package com.unicanal.ai;

/**
 * La IA empezó a escribir una respuesta automática ({@code typing = true}) o desistió sin enviarla
 * ({@code typing = false}). Cuando sí la envía, el mensaje nuevo reemplaza el aviso en la interfaz.
 */
public record AiTypingEvent(String tenantId, String conversationId, boolean typing) {
}
