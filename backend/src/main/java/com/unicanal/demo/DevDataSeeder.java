package com.unicanal.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Carga los datos demo al arrancar. Solo existe con el perfil {@code dev} Y con {@code SEED_DEMO=true}, y es
 * idempotente: si la empresa demo ya existe, no crea nada (se puede dejar activado). Los datos en sí están en
 * {@link DemoData}, que también usa el botón "Restaurar datos demo" del Simulador.
 * Las credenciales demo están en el README (son solo para desarrollo local).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    public static final String COMPANY = DemoData.COMPANY;
    public static final String OWNER_EMAIL = DemoData.OWNER_EMAIL;
    public static final String AGENT_1_EMAIL = DemoData.AGENT_1_EMAIL;
    public static final String AGENT_2_EMAIL = DemoData.AGENT_2_EMAIL;
    public static final String DEMO_PASSWORD = DemoData.DEMO_PASSWORD;

    private final DemoData demoData;

    public DevDataSeeder(DemoData demoData) {
        this.demoData = demoData;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Devuelve true si creó los datos; false si ya existían. */
    public boolean seed() {
        return demoData.createIfMissing();
    }
}
