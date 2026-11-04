package com.unicanal.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "El nombre de la empresa es obligatorio")
        @Size(max = 100, message = "Máximo 100 caracteres") String companyName,
        @NotBlank(message = "Tu nombre es obligatorio") @Size(max = 100, message = "Máximo 100 caracteres") String ownerName,
        @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo inválido")
        @Size(max = 254, message = "Máximo 254 caracteres") String email,
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "Debe tener entre 8 y 72 caracteres") String password) {
}
