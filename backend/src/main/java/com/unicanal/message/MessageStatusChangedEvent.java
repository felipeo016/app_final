package com.unicanal.message;

/** Se publica cuando un `status` del canal cambia el estado de entrega de un mensaje (Paso 6 lo difunde). */
public record MessageStatusChangedEvent(String tenantId, String conversationId, String messageId,
        DeliveryStatus deliveryStatus) {
}
