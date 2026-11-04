package com.unicanal.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class AppPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void emptySecretIsRejected() {
        assertThat(validator.validate(new AppProperties(new AppProperties.Jwt("", 60)))).isNotEmpty();
    }

    @Test
    void shortSecretIsRejected() {
        assertThat(validator.validate(new AppProperties(new AppProperties.Jwt("short", 60)))).isNotEmpty();
    }

    @Test
    void secretOfAtLeast32CharactersIsAccepted() {
        String secret = "a".repeat(32);

        assertThat(validator.validate(new AppProperties(new AppProperties.Jwt(secret, 60)))).isEmpty();
    }
}
