package com.unicanal.demo;

import com.unicanal.common.AuthPrincipal;
import com.unicanal.demo.LiveDemoService.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Herramientas para presentar la demo (solo perfil dev; fuera de dev estas rutas responden 404): la demo en
 * vivo de la empresa del JWT y "Restaurar datos demo".
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevDemoController {

    private final LiveDemoService liveDemo;
    private final DemoRestoreService restoreService;

    public DevDemoController(LiveDemoService liveDemo, DemoRestoreService restoreService) {
        this.liveDemo = liveDemo;
        this.restoreService = restoreService;
    }

    @GetMapping("/live-demo")
    public Status liveDemoStatus(@AuthenticationPrincipal AuthPrincipal principal) {
        return liveDemo.status(principal.tenantId());
    }

    @PostMapping("/live-demo/start")
    public Status startLiveDemo(@AuthenticationPrincipal AuthPrincipal principal) {
        return liveDemo.start(principal.tenantId());
    }

    @PostMapping("/live-demo/stop")
    public Status stopLiveDemo(@AuthenticationPrincipal AuthPrincipal principal) {
        liveDemo.stop(principal.tenantId());
        return liveDemo.status(principal.tenantId());
    }

    /** Detiene la demo en vivo y deja la empresa demo como recién creada. La interfaz pide confirmación antes. */
    @PostMapping("/demo/restore")
    public RestoreResponse restoreDemoData(@AuthenticationPrincipal AuthPrincipal principal) {
        restoreService.restore(principal);
        return new RestoreResponse(true, DemoData.COMPANY);
    }

    public record RestoreResponse(boolean restored, String company) {
    }
}
