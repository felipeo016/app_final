package com.unicanal.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.config.AppProperties;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Emite y valida JWT HS256 con claims: sub = userId, tenantId, role. */
@Service
public class JwtService {

    static final String ISSUER = "unicanal";
    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_ROLE = "role";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public JwtService(AppProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(AppProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbusDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbusDecoder;
        this.ttl = Duration.ofMinutes(properties.jwt().expirationMinutes());
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(CLAIM_TENANT_ID, user.getTenantId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** Devuelve la identidad del token, o vacío si es inválido, está vencido o mal firmado. */
    public Optional<AuthPrincipal> parse(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            String tenantId = jwt.getClaimAsString(CLAIM_TENANT_ID);
            String role = jwt.getClaimAsString(CLAIM_ROLE);
            if (jwt.getSubject() == null || tenantId == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthPrincipal(jwt.getSubject(), tenantId, Role.valueOf(role)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
