package com.unicanal.auth;

import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.LoginRequest;
import com.unicanal.auth.dto.MeResponse;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.common.ApiException;
import com.unicanal.common.Emails;
import com.unicanal.tenant.Tenant;
import com.unicanal.tenant.TenantRepository;
import com.unicanal.tenant.dto.TenantResponse;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import com.unicanal.user.dto.UserResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository,
            PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Crea la empresa (tenant) y su usuario OWNER. Si falla la creación del usuario, deshace la empresa. */
    public AuthResponse register(RegisterRequest request) {
        String email = Emails.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }
        Tenant tenant = tenantRepository.save(new Tenant(request.companyName().trim()));
        User owner;
        try {
            owner = userRepository.save(new User(tenant.getId(), request.ownerName().trim(), email,
                    passwordEncoder.encode(request.password()), Role.OWNER));
        } catch (DuplicateKeyException e) {
            tenantRepository.delete(tenant);
            throw emailTaken();
        } catch (RuntimeException e) {
            tenantRepository.delete(tenant);
            throw e;
        }
        return authResponse(owner, tenant);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(Emails.normalize(request.email()))
                .filter(User::isActive)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Correo o contraseña incorrectos"));
        Tenant tenant = tenantOf(user);
        return authResponse(user, tenant);
    }

    public MeResponse me(String tenantId, String userId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Autenticación requerida"));
        return new MeResponse(UserResponse.from(user), TenantResponse.from(tenantOf(user)));
    }

    private Tenant tenantOf(User user) {
        return tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Autenticación requerida"));
    }

    private AuthResponse authResponse(User user, Tenant tenant) {
        JwtService.IssuedToken token = jwtService.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserResponse.from(user),
                TenantResponse.from(tenant));
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Ese correo ya está registrado");
    }
}
