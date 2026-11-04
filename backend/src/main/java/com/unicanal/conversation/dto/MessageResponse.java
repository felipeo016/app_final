package com.unicanal.conversation.dto;

import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageType;
import java.time.Instant;

/** {@code failureReason} solo viene con {@code deliveryStatus = FAILED}: la interfaz lo muestra al agente. */
public record MessageResponse(String id, String conversationId, Direction direction, MessageType type, String content,
        String sentBy, Instant timestamp, DeliveryStatus deliveryStatus, String failureReason) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(message.getId(), message.getConversationId(), message.getDirection(),
                message.getType(), message.getContent(), message.getSentBy(), message.getTimestamp(),
                message.getDeliveryStatus(), message.getFailureReason());
    }
}
