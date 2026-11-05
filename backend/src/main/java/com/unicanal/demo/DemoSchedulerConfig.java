package com.unicanal.demo;

import java.util.concurrent.ScheduledFuture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Hilo propio para la demo en vivo (solo perfil dev). No depende de {@code @EnableScheduling}: la demo funciona
 * aunque las tareas periódicas estén desactivadas, y al cerrar la aplicación el hilo se detiene.
 */
@Configuration
@Profile("dev")
class DemoSchedulerConfig {

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler liveDemoTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("demo-en-vivo-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    DemoScheduler demoScheduler(ThreadPoolTaskScheduler liveDemoTaskScheduler) {
        return (task, delay) -> {
            // La hora se calcula con el reloj del propio planificador (el real), no con el de la aplicación
            ScheduledFuture<?> future = liveDemoTaskScheduler.schedule(task,
                    liveDemoTaskScheduler.getClock().instant().plus(delay));
            return () -> future.cancel(false);
        };
    }
}
