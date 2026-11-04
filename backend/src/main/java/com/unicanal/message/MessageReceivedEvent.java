package com.unicanal.message;

/**
 * Se publica tras guardar un mensaje entrante. El Paso 5 lo usa para la asignación por turnos de
 * los chats nuevos y el Paso 6 para notificar por WebSocket.
 */
public record MessageReceivedEvent(String tenantId, String contactId, String conversationId, String messageId,
        boolean newConversation) {
}
