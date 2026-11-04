package com.unicanal.channel;

import com.unicanal.common.ApiException;
import org.springframework.http.HttpStatus;

/** El cuerpo del webhook no es un JSON con la estructura esperada. */
public class InvalidWebhookPayloadException extends ApiException {

    public InvalidWebhookPayloadException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "El cuerpo del webhook no es válido");
    }
}
