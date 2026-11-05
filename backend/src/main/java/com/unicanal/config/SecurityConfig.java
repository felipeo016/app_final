package com.unicanal.config;

import com.unicanal.auth.JwtAuthFilter;
import com.unicanal.auth.PrincipalResolver;
import com.unicanal.common.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PrincipalResolver principalResolver,
            JsonMapper jsonMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // Webhooks de canales: sin JWT (Meta no lo envía); se autentican por firma.
                        .requestMatchers("/api/webhooks/**").permitAll()
                        // WebSocket: el handshake HTTP no lleva JWT (los navegadores no pueden enviar cabeceras);
                        // la autenticación se hace en el frame STOMP CONNECT (ver StompAuthInterceptor).
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint(jsonMapper))
                        .accessDeniedHandler(accessDeniedHandler(jsonMapper)))
                .addFilterBefore(new JwtAuthFilter(principalResolver),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(JsonMapper jsonMapper) {
        return (request, response, ex) -> writeError(response, jsonMapper,
                ErrorResponse.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Autenticación requerida"));
    }

    private AccessDeniedHandler accessDeniedHandler(JsonMapper jsonMapper) {
        return (request, response, ex) -> writeError(response, jsonMapper,
                ErrorResponse.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tienes permisos para esta acción"));
    }

    private void writeError(HttpServletResponse response, JsonMapper jsonMapper, ErrorResponse body)
            throws java.io.IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }
}
