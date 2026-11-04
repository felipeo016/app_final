package com.unicanal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unicanal.Fixtures;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.LoginRequest;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.tenant.Tenant;
import com.unicanal.tenant.TenantRepository;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = new JwtService(Fixtures.properties());
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(tenantRepository, userRepository, passwordEncoder, jwtService);
    }

    @Test
    void registerCreatesTenantAndOwnerAndReturnsToken() {
        when(userRepository.existsByEmail("owner@acme.com")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> {
            Tenant tenant = invocation.getArgument(0);
            ReflectionTestUtils.setField(tenant, "id", "tenant-1");
            return tenant;
        });
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", "user-1");
            return user;
        });

        AuthResponse response = service.register(
                new RegisterRequest("  Acme  ", "Ana", "  Owner@Acme.com ", "s3cret-pass"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User owner = saved.getValue();
        assertThat(owner.getTenantId()).isEqualTo("tenant-1");
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(owner.getEmail()).isEqualTo("owner@acme.com");
        assertThat(owner.isActive()).isTrue();
        assertThat(owner.getPasswordHash()).isNotEqualTo("s3cret-pass");
        assertThat(passwordEncoder.matches("s3cret-pass", owner.getPasswordHash())).isTrue();

        assertThat(response.tenant().name()).isEqualTo("Acme");
        assertThat(jwtService.parse(response.token()))
                .contains(new AuthPrincipal("user-1", "tenant-1", Role.OWNER));
    }

    @Test
    void registerWithTakenEmailFailsWithoutCreatingTenant() {
        when(userRepository.existsByEmail("owner@acme.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("Acme", "Ana", "owner@acme.com", "s3cret-pass")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void registerRollsBackTenantWhenEmailIsTakenConcurrently() {
        Tenant tenant = Fixtures.tenant("tenant-1", "Acme");
        when(userRepository.existsByEmail("owner@acme.com")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(tenant);
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("Acme", "Ana", "owner@acme.com", "s3cret-pass")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(tenantRepository).delete(tenant);
    }

    @Test
    void loginWithValidCredentialsReturnsToken() {
        User user = Fixtures.user("u1", "t1", Role.AGENT, "agent@acme.com", passwordEncoder.encode("s3cret-pass"));
        when(userRepository.findByEmail("agent@acme.com")).thenReturn(Optional.of(user));
        when(tenantRepository.findById("t1")).thenReturn(Optional.of(Fixtures.tenant("t1", "Acme")));

        AuthResponse response = service.login(new LoginRequest(" Agent@Acme.com", "s3cret-pass"));

        assertThat(jwtService.parse(response.token())).contains(new AuthPrincipal("u1", "t1", Role.AGENT));
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        User user = Fixtures.user("u1", "t1", Role.AGENT, "agent@acme.com", passwordEncoder.encode("s3cret-pass"));
        when(userRepository.findByEmail("agent@acme.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("agent@acme.com", "wrong-pass")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void loginWithUnknownEmailIsUnauthorizedWithSameError() {
        when(userRepository.findByEmail("ghost@acme.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@acme.com", "whatever1")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS");
                });
    }

    @Test
    void deactivatedUserCannotLogIn() {
        User user = Fixtures.user("u1", "t1", Role.AGENT, "agent@acme.com", passwordEncoder.encode("s3cret-pass"));
        user.setActive(false);
        when(userRepository.findByEmail("agent@acme.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest("agent@acme.com", "s3cret-pass")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
