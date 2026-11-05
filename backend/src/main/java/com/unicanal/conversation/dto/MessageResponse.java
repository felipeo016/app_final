package com.unicanal.conversation.dto;

import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageType;
import java.time.Instant;

/**
 * {@code failureReason} y {@code failureCode} solo vienen con {@code deliveryStatus = FAILED}: el motivo se muestra
 * al agente; con el código CHANNEL_SEND_UNCONFIRMED la interfaz pide confirmación antes de reintentar.
 * {@code automatic} marca las respuestas que escribió y envió la IA.
 */
public record MessageResponse(String id, String conversationId, Direction direction, MessageType type, String content,
        String sentBy, Instant timestamp, DeliveryStatus deliveryStatus, String failureReason, String failureCode,
        boolean automatic) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(message.getId(), message.getConversationId(), message.getDirection(),
                message.getType(), message.getContent(), message.getSentBy(), message.getTimestamp(),
                message.getDeliveryStatus(), message.getFailureReason(), message.getFailureCode(),
                message.isAutomatic());
    }
}
