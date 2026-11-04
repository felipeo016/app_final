package com.unicanal.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Cuerpo estándar de todos los errores de la API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String code, String message, Map<String, String> fieldErrors) {

    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, null);
    }

    public static ErrorResponse validation(Map<String, String> fieldErrors) {
        return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR",
                "Hay datos inválidos en la solicitud", fieldErrors);
    }
}
