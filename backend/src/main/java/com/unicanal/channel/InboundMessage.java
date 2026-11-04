package com.unicanal.channel;

import com.unicanal.message.MessageType;
import java.time.Instant;

/**
 * Mensaje entrante ya normalizado, independiente del canal.
 *
 * @param accountId          cuenta del canal que lo recibió (phone_number_id en WhatsApp); sirve para
 *                           resolver a qué empresa pertenece
 * @param contactExternalId  teléfono o usuario del cliente en el canal
 * @param contactName        nombre del perfil si el canal lo informa; puede ser null
 * @param externalMessageId  id del mensaje en el canal; base de la idempotencia; puede ser null
 */
public record InboundMessage(ChannelType channel, String accountId, String contactExternalId, String contactName,
        String externalMessageId, MessageType type, String content, Instant timestamp) {
}
