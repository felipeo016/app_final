package com.unicanal;

import com.unicanal.config.AppProperties;
import com.unicanal.tenant.Tenant;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import org.springframework.test.util.ReflectionTestUtils;

/** Fábricas de datos para pruebas unitarias. */
public final class Fixtures {

    public static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";

    private Fixtures() {
    }

    public static AppProperties properties(String secret, long expirationMinutes) {
        return new AppProperties(new AppProperties.Jwt(secret, expirationMinutes));
    }

    public static AppProperties properties() {
        return properties(SECRET, 60);
    }

    public static Tenant tenant(String id, String name) {
        Tenant tenant = new Tenant(name);
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    public static User user(String id, String tenantId, Role role, String email, String passwordHash) {
        User user = new User(tenantId, "Usuario " + id, email, passwordHash, role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
