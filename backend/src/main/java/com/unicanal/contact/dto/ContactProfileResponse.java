package com.unicanal.contact.dto;

import com.unicanal.channel.ChannelType;
import com.unicanal.contact.ContactIdentity;
import com.unicanal.conversation.ConversationStatus;
import java.time.Instant;
import java.util.List;

/** Perfil del cliente con su historial de conversaciones (la más reciente primero). */
public record ContactProfileResponse(String id, String name, List<ContactIdentity> identities, String notes,
        Instant createdAt, List<ConversationHistoryItem> conversations) {

    public record ConversationHistoryItem(String id, ChannelType channel, ConversationStatus status, boolean open,
            String lastMessagePreview, Instant lastMessageAt, Instant createdAt) {
    }
}
