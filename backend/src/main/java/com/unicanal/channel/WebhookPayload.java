package com.unicanal.channel;

import java.util.List;

/** Todo lo que trae un webhook: puede incluir varios mensajes y varios estados. */
public record WebhookPayload(List<InboundMessage> messages, List<StatusUpdate> statuses) {

    public static WebhookPayload empty() {
        return new WebhookPayload(List.of(), List.of());
    }
}
