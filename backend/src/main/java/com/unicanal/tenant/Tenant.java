package com.unicanal.tenant;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Empresa (tenant). Su id es el tenantId que llevan todos los demás documentos. */
@Document("tenants")
public class Tenant {

    @Id
    private String id;
    private String name;
    private TenantSettings settings = new TenantSettings();
    /** Contador atómico ($inc) para la asignación por turnos (Paso 5). */
    private long roundRobinCursor;
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public TenantSettings getSettings() {
        return settings;
    }

    public long getRoundRobinCursor() {
        return roundRobinCursor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
