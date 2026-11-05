package com.unicanal.auth;

import com.unicanal.common.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extrae el JWT de la cabecera Authorization y deja el {@link AuthPrincipal} en el contexto de seguridad.
 * La validación (token válido, usuario activo y del mismo tenant) la hace {@link PrincipalResolver}, la
 * misma que usa el WebSocket. Con un token ausente o inválido no autentica y deja que Spring Security
 * responda 401 en las rutas protegidas.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PrincipalResolver principalResolver;

    public JwtAuthFilter(PrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            principalResolver.resolve(header.substring(BEARER_PREFIX.length())).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
