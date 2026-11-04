package com.unicanal.user;

import java.time.Instant;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

@Document("users")
@CompoundIndex(name = "tenant_role_active", def = "{'tenantId': 1, 'role': 1, 'active': 1}")
public class User {

    @MongoId(FieldType.STRING)
    private String id;
    private String tenantId;
    private String name;
    /** Normalizado (trim + minúsculas). Único global: el login no recibe tenant. */
    @Indexed(unique = true)
    private String email;
    private String passwordHash;
    private Role role;
    private boolean active;
    private Instant createdAt;

    protected User() {
    }

    public User(String tenantId, String name, String email, String passwordHash, Role role) {
        this.tenantId = tenantId;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
