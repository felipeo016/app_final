package com.unicanal.demo;

import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.realtime.WebSocketSessionRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * "Restaurar datos demo" (solo perfil dev): detiene la demo en vivo, deja la empresa demo como recién creada y
 * avisa a los navegadores conectados para que recarguen.
 *
 * <p>Solo un usuario de la propia empresa demo puede restaurarla: así, quien esté probando con otra empresa en el
 * mismo backend nunca borra por error los datos de la feria (ni los suyos).
 */
@Service
@Profile("dev")
public class DemoRestoreService {

    private static final Logger log = LoggerFactory.getLogger(DemoRestoreService.class);

    private final DemoData demoData;
    private final LiveDemoService liveDemo;
    private final WebSocketSessionRegistry sessions;

    public DemoRestoreService(DemoData demoData, LiveDemoService liveDemo, WebSocketSessionRegistry sessions) {
        this.demoData = demoData;
        this.liveDemo = liveDemo;
        this.sessions = sessions;
    }

    public void restore(AuthPrincipal principal) {
        Optional<String> demoTenant = demoData.tenantId();
        if (demoTenant.isPresent() && !demoTenant.get().equals(principal.tenantId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_DEMO_COMPANY",
                    "Solo se pueden restaurar los datos desde la empresa demo (" + DemoData.COMPANY + ")");
        }
        if (demoTenant.isEmpty()) {
            // Sin empresa demo: se crea, pero la del usuario actual no se toca
            demoData.createIfMissing();
            return;
        }
        String tenantId = demoTenant.get();
        liveDemo.stop(tenantId);
        liveDemo.resetScript(tenantId);
        demoData.restore();
        // Los navegadores de la empresa se reconectan solos (1012) y, al reconectar, recargan la bandeja
        int closed = sessions.closeSessionsOfTenant(tenantId, "Datos demo restaurados");
        log.info("Datos demo restaurados por el usuario {} ({} sesiones avisadas)", principal.userId(), closed);
    }
}
