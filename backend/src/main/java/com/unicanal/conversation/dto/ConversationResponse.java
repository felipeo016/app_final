package com.unicanal.conversation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.unicanal.channel.ChannelType;
import com.unicanal.conversation.ConversationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Conversación tal como la ve la bandeja. {@code replyWindowOpen} indica si se puede responder con texto
 * libre (en WhatsApp, dentro de las 24 h desde el último mensaje del cliente; en otros canales, siempre);
 * {@code replyWindowExpiresAt} solo se informa en WhatsApp.
 */
public record ConversationResponse(String id, ContactRef contact, ChannelType channel, ConversationStatus status,
        boolean open, UserRef assignedTo, String lastMessagePreview, Instant lastMessageAt, Instant lastInboundAt,
        Instant lastOutboundAt, Instant firstResponseAt, int unreadCount, boolean atRisk, Instant atRiskSince, boolean replyWindowOpen,
        Instant replyWindowExpiresAt, @JsonProperty("isSale") boolean isSale, BigDecimal saleAmount, List<String> tags, Instant createdAt) {

    public record ContactRef(String id, String name) {
    }

    public record UserRef(String id, String name) {
    }
}
