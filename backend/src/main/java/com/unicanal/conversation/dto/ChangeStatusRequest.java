package com.unicanal.conversation.dto;

import com.unicanal.conversation.ConversationStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeStatusRequest(@NotNull(message = "El estado es obligatorio") ConversationStatus status) {
}
