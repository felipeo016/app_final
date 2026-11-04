package com.unicanal.user;

/**
 * Se publica cuando un agente se desactiva. El módulo de conversaciones lo escuchará (Paso 5)
 * para dejar sus chats abiertos sin asignar y emitir ASSIGNED.
 */
public record AgentDeactivatedEvent(String tenantId, String agentId) {
}
