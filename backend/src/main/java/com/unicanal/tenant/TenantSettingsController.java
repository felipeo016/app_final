package com.unicanal.tenant;

import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuración de la empresa del JWT. Solo el OWNER la ve y la cambia. */
@RestController
@RequestMapping("/api/tenant/settings")
@PreAuthorize("hasRole('OWNER')")
public class TenantSettingsController {

    private final TenantRepository tenants;
    private final MongoTemplate mongoTemplate;

    public TenantSettingsController(TenantRepository tenants, MongoTemplate mongoTemplate) {
        this.tenants = tenants;
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public SettingsResponse get(@AuthenticationPrincipal AuthPrincipal principal) {
        Tenant tenant = tenants.findById(principal.tenantId())
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Empresa no encontrada"));
        return new SettingsResponse(tenant.getSettings().getRiskAlertMinutes());
    }

    /** La alerta de riesgo lee la configuración en cada pasada: el cambio aplica en la siguiente. */
    @PutMapping
    public SettingsResponse update(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateSettingsRequest request) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(principal.tenantId())),
                Update.update("settings.riskAlertMinutes", request.riskAlertMinutes()), Tenant.class);
        return get(principal);
    }

    public record SettingsResponse(int riskAlertMinutes) {
    }

    public record UpdateSettingsRequest(
            @NotNull(message = "Indica los minutos de la alerta")
            @Min(value = 1, message = "Debe estar entre 1 y 1440 minutos")
            @Max(value = 1440, message = "Debe estar entre 1 y 1440 minutos") Integer riskAlertMinutes) {
    }
}
