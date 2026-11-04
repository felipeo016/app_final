package com.unicanal.auth;

import com.unicanal.common.AuthPrincipal;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extrae userId y tenantId del JWT y deja el {@link AuthPrincipal} en el contexto de seguridad.
 * Además confirma que el usuario sigue activo y pertenece a ese tenant, de modo que desactivar un
 * agente le corta el acceso aunque su token no haya vencido. Con un token ausente o inválido no
 * autentica y deja que Spring Security responda 401 en las rutas protegidas.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        Optional<AuthPrincipal> claims = jwtService.parse(token);
        if (claims.isEmpty()) {
            return;
        }
        AuthPrincipal fromToken = claims.get();
        userRepository.findById(fromToken.userId())
                .filter(User::isActive)
                .filter(user -> user.getTenantId().equals(fromToken.tenantId()))
                .ifPresent(user -> {
                    // El rol se toma de la base: un cambio de rol aplica de inmediato.
                    AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getTenantId(), user.getRole());
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
    }
}
