package com.unicanal.ai;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** Al arrancar, precarga el modelo en segundo plano para que la primera respuesta no tarde de más. */
@Configuration
public class AiConfig {

    private final AiProperties properties;
    private final AiProvider provider;

    public AiConfig(AiProperties properties, AiProvider provider) {
        this.properties = properties;
        this.provider = provider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (properties.warmUp()) {
            Thread.ofVirtual().name("ia-precarga").start(provider::warmUp);
        }
    }
}
