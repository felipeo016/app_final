package com.unicanal.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unicanal.conversation.dto.ConversationResponse;
import com.unicanal.conversation.dto.MessageResponse;
import java.time.Instant;

/**
 * Evento que recibe la interfaz por WebSocket. Siempre trae la conversación completa, tal como quedó en
 * MongoDB (vista previa, estado, asignado, no leídos, atRisk...), para que la bandeja se actualice sin
 * otra petición. {@code message} viene cuando el evento trata de un mensaje concreto, con su
 * {@code deliveryStatus} (y {@code failureReason} si falló).
 *
 * <ul>
 *   <li>{@code MESSAGE_CREATED}: mensaje nuevo (del cliente, respuesta de un agente o nota interna).</li>
 *   <li>{@code CONVERSATION_UPDATED}: cambió la conversación (estado, leído, venta) o el estado de
 *       entrega de un mensaje (entonces trae {@code message}).</li>
 *   <li>{@code ASSIGNED}: cambió el agente asignado (nulo = sin asignar).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeEvent(Type type, String conversationId, ConversationResponse conversation,
        MessageResponse message, Instant occurredAt) {

    public enum Type {
        MESSAGE_CREATED,
        CONVERSATION_UPDATED,
        ASSIGNED,
        /** La IA está escribiendo una respuesta automática (la interfaz muestra "La IA está escribiendo…"). */
        AI_TYPING,
        /** La IA desistió de responder: se quita el aviso. Si responde, el mensaje nuevo lo reemplaza. */
        AI_TYPING_STOPPED
    }
}
