package com.unicanal.demo;

import java.time.Duration;

/**
 * Programa tareas a futuro para la demo en vivo. Es una interfaz para que las pruebas puedan sustituir el
 * reloj real por uno manual y ejecutar los mensajes "programados" sin esperar.
 */
public interface DemoScheduler {

    /** Ejecuta {@code task} dentro de {@code delay}. El resultado permite cancelarla. */
    Scheduled schedule(Runnable task, Duration delay);

    interface Scheduled {
        void cancel();
    }
}
