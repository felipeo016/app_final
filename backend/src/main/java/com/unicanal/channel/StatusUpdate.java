package com.unicanal.channel;

import com.unicanal.message.DeliveryStatus;
import java.time.Instant;

/** Cambio en el estado de entrega de un mensaje saliente (sent, delivered, read, failed). */
public record StatusUpdate(ChannelType channel, String accountId, String externalMessageId,
        DeliveryStatus status, Instant timestamp) {
}
