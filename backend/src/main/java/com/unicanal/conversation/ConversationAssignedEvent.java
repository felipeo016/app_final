package com.unicanal.conversation;

/**
 * Cambió el agente asignado a una conversación ({@code assignedTo} nulo = quedó sin asignar). El Paso 6 lo
 * difunde por WebSocket como ASSIGNED.
 */
public record ConversationAssignedEvent(String tenantId, String conversationId, String assignedTo) {
}
