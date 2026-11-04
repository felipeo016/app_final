package com.unicanal.conversation;

/** Cambió el estado u otros datos visibles de una conversación (Paso 6: CONVERSATION_UPDATED). */
public record ConversationUpdatedEvent(String tenantId, String conversationId) {
}
