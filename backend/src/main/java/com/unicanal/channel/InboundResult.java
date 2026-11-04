package com.unicanal.channel;

/**
 * Resultado de procesar un mensaje entrante.
 *
 * @param duplicate true si el mensaje ya estaba guardado (reintento del canal) y no se repitió
 */
public record InboundResult(boolean duplicate, String contactId, String conversationId, String messageId,
        boolean newConversation) {

    static InboundResult duplicate(String conversationId, String messageId) {
        return new InboundResult(true, null, conversationId, messageId, false);
    }
}
