package com.unicanal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tareas periódicas (cierre de WebSocket con token vencido, alerta de chats en riesgo). Las pruebas lo
 * desactivan ({@code app.scheduling.enabled=false}) e invocan las tareas a mano con un reloj controlado,
 * para no depender de esperas reales ni de pasadas que corran en paralelo con la prueba.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
