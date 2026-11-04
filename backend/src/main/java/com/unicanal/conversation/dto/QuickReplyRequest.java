package com.unicanal.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuickReplyRequest(
        @NotBlank(message = "El título es obligatorio") @Size(max = 80, message = "Máximo 80 caracteres") String title,
        @NotBlank(message = "El texto es obligatorio") @Size(max = 4096, message = "Máximo 4096 caracteres")
        String text) {
}
