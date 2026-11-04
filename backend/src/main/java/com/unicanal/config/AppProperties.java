package com.unicanal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(@Valid @NotNull Jwt jwt) {

    public record Jwt(
            @NotBlank(message = "Define la variable de entorno JWT_SECRET")
            @Size(min = 32, message = "JWT_SECRET debe tener al menos 32 caracteres") String secret,
            @Min(1) long expirationMinutes) {
    }
}
