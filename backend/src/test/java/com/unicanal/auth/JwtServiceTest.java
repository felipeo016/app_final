package com.unicanal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.Fixtures;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final User user = Fixtures.user("u1", "t1", Role.OWNER, "a@b.com", "hash");

    @Test
    void issuedTokenCarriesUserTenantAndRole() {
        JwtService service = new JwtService(Fixtures.properties());

        String token = service.issue(user).value();

        assertThat(service.parse(token)).contains(new AuthPrincipal("u1", "t1", Role.OWNER));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService issuer = new JwtService(Fixtures.properties("another-secret-another-secret-0123456789", 60));
        JwtService verifier = new JwtService(Fixtures.properties());

        assertThat(verifier.parse(issuer.issue(user).value())).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
        JwtService service = new JwtService(Fixtures.properties(), past);

        assertThat(service.parse(service.issue(user).value())).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = new JwtService(Fixtures.properties());
        String token = service.issue(user).value();
        String[] parts = token.split("\\.");
        // Reemplaza el payload por otro tenant manteniendo la firma original.
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"u1\",\"tenantId\":\"other-tenant\",\"role\":\"OWNER\",\"iss\":\"unicanal\"}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(service.parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        JwtService service = new JwtService(Fixtures.properties());

        assertThat(service.parse("not-a-jwt")).isEmpty();
        assertThat(service.parse("")).isEmpty();
    }

    @Test
    void expirationReflectsConfiguredTtl() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
        JwtService service = new JwtService(Fixtures.properties(Fixtures.SECRET, 480), fixed);

        assertThat(service.issue(user).expiresAt()).isEqualTo(Instant.parse("2026-11-04T18:00:00Z"));
    }
}
