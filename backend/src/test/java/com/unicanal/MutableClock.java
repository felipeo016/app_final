package com.unicanal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reloj de pruebas: por defecto sigue la hora real, pero se puede fijar o adelantar para simular el paso
 * del tiempo sin esperas reales. {@link #reset()} vuelve a la hora real.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant now = fixed.get();
        return now != null ? now : Instant.now();
    }

    public void set(Instant instant) {
        fixed.set(instant);
    }

    /** Congela el reloj en la hora actual (si no lo estaba) y lo adelanta. */
    public Instant advance(Duration duration) {
        return fixed.updateAndGet(now -> (now != null ? now : Instant.now()).plus(duration));
    }

    public void reset() {
        fixed.set(null);
    }
}
