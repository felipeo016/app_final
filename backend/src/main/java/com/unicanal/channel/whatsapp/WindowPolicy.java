package com.unicanal.channel.whatsapp;

import java.time.Duration;
import java.time.Instant;

/**
 * Regla de la ventana de 24 horas de WhatsApp: solo se puede escribir texto libre dentro de las 24 h
 * posteriores al último mensaje del cliente. Pasado ese plazo solo se permiten plantillas aprobadas.
 */
public final class WindowPolicy {

    public static final Duration WINDOW = Duration.ofHours(24);

    private WindowPolicy() {
    }

    /** Abierta mientras no se cumplan 24 h exactas. Sin mensajes del cliente nunca está abierta. */
    public static boolean isOpen(Instant lastInboundAt, Instant now) {
        return lastInboundAt != null && now.isBefore(lastInboundAt.plus(WINDOW));
    }

    /** Tiempo que queda de ventana (cero si está cerrada). */
    public static Duration remaining(Instant lastInboundAt, Instant now) {
        if (!isOpen(lastInboundAt, now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, lastInboundAt.plus(WINDOW));
    }
}
