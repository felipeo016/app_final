package com.unicanal.auth;

import com.unicanal.common.AuthPrincipal;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Única validación de identidad de la aplicación, compartida por HTTP ({@link JwtAuthFilter}) y WebSocket
 * (frame CONNECT): el JWT debe ser válido y, además, en base de datos el usuario debe seguir activo y
 * pertenecer al tenant del token. Así desactivar a un agente le corta el acceso aunque su token no haya
 * vencido. El rol se toma de la base: un cambio de rol aplica de inmediato.
 */
@Component
public class PrincipalResolver {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public PrincipalResolver(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    public Optional<AuthPrincipal> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jwtService.parse(token.trim()).flatMap(fromToken -> userRepository.findById(fromToken.userId())
                .filter(User::isActive)
                .filter(user -> user.getTenantId().equals(fromToken.tenantId()))
                .map(user -> new AuthPrincipal(user.getId(), user.getTenantId(), user.getRole())));
    }
}
