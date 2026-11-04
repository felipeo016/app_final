package com.unicanal.contact.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Campos nulos = no se cambian. */
public record UpdateContactRequest(
        @Size(max = 100, message = "Máximo 100 caracteres")
        @Pattern(regexp = ".*\\S.*", message = "El nombre no puede estar vacío") String name,
        @Size(max = 2000, message = "Máximo 2000 caracteres") String notes) {
}
