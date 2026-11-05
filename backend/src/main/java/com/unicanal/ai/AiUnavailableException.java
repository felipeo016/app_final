package com.unicanal.ai;

/** El modelo no respondió (servicio apagado, tiempo agotado o error). La app sigue funcionando sin IA. */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiUnavailableException(String message) {
        super(message);
    }
}
