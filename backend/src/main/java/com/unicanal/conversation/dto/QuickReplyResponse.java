package com.unicanal.conversation.dto;

import com.unicanal.conversation.QuickReply;
import java.time.Instant;

public record QuickReplyResponse(String id, String title, String text, Instant createdAt) {

    public static QuickReplyResponse from(QuickReply reply) {
        return new QuickReplyResponse(reply.getId(), reply.getTitle(), reply.getText(), reply.getCreatedAt());
    }
}
