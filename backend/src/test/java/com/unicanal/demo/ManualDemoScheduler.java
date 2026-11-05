package com.unicanal.demo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Planificador de pruebas: guarda las tareas de la demo en vivo en vez de esperar. La prueba las ejecuta con
 * {@link #runNext()}, así se recorre el guion completo sin esperas reales.
 */
public class ManualDemoScheduler implements DemoScheduler {

    public static final class Task implements Scheduled {
        final Runnable runnable;
        final Duration delay;
        boolean cancelled;

        Task(Runnable runnable, Duration delay) {
            this.runnable = runnable;
            this.delay = delay;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        public Duration delay() {
            return delay;
        }

        public boolean cancelled() {
            return cancelled;
        }
    }

    private final List<Task> tasks = new ArrayList<>();

    @Override
    public synchronized Scheduled schedule(Runnable task, Duration delay) {
        Task scheduled = new Task(task, delay);
        tasks.add(scheduled);
        return scheduled;
    }

    /** Tareas aún no ejecutadas (canceladas o no). */
    public synchronized List<Task> pending() {
        return List.copyOf(tasks);
    }

    /** Ejecuta la tarea más antigua (aunque esté cancelada: así se prueba que una cancelada no hace nada). */
    public Task runNext() {
        Task next;
        synchronized (this) {
            if (tasks.isEmpty()) {
                throw new IllegalStateException("No hay tareas programadas");
            }
            next = tasks.remove(0);
        }
        next.runnable.run();
        return next;
    }

    public synchronized void clear() {
        tasks.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        ManualDemoScheduler manualDemoScheduler() {
            return new ManualDemoScheduler();
        }
    }
}
