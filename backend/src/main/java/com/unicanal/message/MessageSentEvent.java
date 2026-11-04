package com.unicanal.message;

/**
 * Un agente escribió en una conversación (respuesta al cliente o nota interna). {@code deliveryStatus} es
 * SENT o FAILED para las respuestas y nulo para las notas. El Paso 6 lo difunde por WebSocket.
 */
public record MessageSentEvent(String tenantId, String conversationId, String messageId,
        DeliveryStatus deliveryStatus) {
}
