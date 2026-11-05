package com.unicanal.common;

/**
 * Error de validación que depende de varios campos a la vez (no expresable con anotaciones). Sale con el
 * mismo formato que los errores de jakarta.validation: 400 VALIDATION_ERROR y {@code fieldErrors}.
 */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
