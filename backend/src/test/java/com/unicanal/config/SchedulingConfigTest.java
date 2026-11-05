package com.unicanal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Las tareas periódicas (alerta de chats en riesgo, cierre de WebSocket vencidos) deben estar activas fuera
 * de las pruebas. Solo {@code src/test/resources/application.properties} las desactiva.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    /** Entorno con la configuración real de la aplicación (no la de pruebas) para los perfiles indicados. */
    private static StandardEnvironment applicationEnvironment(String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Los de perfil tienen prioridad sobre el general, como en Spring Boot
        for (String profile : profiles) {
            addIfPresent(environment, "application-" + profile + ".yml");
            addIfPresent(environment, "application-" + profile + ".yaml");
            addIfPresent(environment, "application-" + profile + ".properties");
        }
        addIfPresent(environment, "application.yml");
        return environment;
    }

    private static void addIfPresent(StandardEnvironment environment, String name) throws IOException {
        Resource resource = new ClassPathResource(name);
        if (!resource.exists()) {
            return;
        }
        var sources = name.endsWith(".properties")
                ? new PropertiesPropertySourceLoader().load(name, resource)
                : new YamlPropertySourceLoader().load(name, resource);
        sources.forEach(source -> environment.getPropertySources().addLast(source));
    }

    @Test
    void applicationYmlEnablesSchedulingByDefaultAndInTheDevProfile() throws IOException {
        // application.yml es el de src/main (en pruebas no hay otro .yml que lo tape)
        assertThat(new ClassPathResource("application.yml").exists()).isTrue();

        for (String[] profiles : new String[][] {{}, {"dev"}}) {
            StandardEnvironment environment = applicationEnvironment(profiles);
            assertThat(environment.getProperty("app.scheduling.enabled", Boolean.class))
                    .as("app.scheduling.enabled con perfiles %s", String.join(",", profiles))
                    .isTrue();
        }
    }

    @Test
    void schedulingIsActiveWhenThePropertyIsTrueOrMissing() {
        runner.run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
        runner.withPropertyValues("app.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void onlyAnExplicitFalseDisablesIt() {
        runner.withPropertyValues("app.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
