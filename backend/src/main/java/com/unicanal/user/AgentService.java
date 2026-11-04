package com.unicanal.user;

import com.unicanal.common.ApiException;
import com.unicanal.common.Emails;
import com.unicanal.user.dto.CreateAgentRequest;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Gestión de agentes de una empresa. Todo se acota por el tenantId del JWT. */
@Service
public class AgentService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public AgentService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    public List<User> list(String tenantId) {
        return userRepository.findByTenantIdAndRoleOrderByCreatedAtAsc(tenantId, Role.AGENT);
    }

    public User create(String tenantId, CreateAgentRequest request) {
        String email = Emails.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }
        User agent = new User(tenantId, request.name().trim(), email,
                passwordEncoder.encode(request.password()), Role.AGENT);
        try {
            return userRepository.save(agent);
        } catch (DuplicateKeyException e) {
            throw emailTaken();
        }
    }

    /** Desactiva al agente (idempotente). El OWNER no se puede desactivar. */
    public User deactivate(String tenantId, String agentId) {
        User user = userRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> ApiException.notFound("AGENT_NOT_FOUND", "Agente no encontrado"));
        if (user.getRole() == Role.OWNER) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DEACTIVATE_OWNER",
                    "No se puede desactivar al dueño de la empresa");
        }
        if (!user.isActive()) {
            return user;
        }
        user.setActive(false);
        User saved = userRepository.save(user);
        events.publishEvent(new AgentDeactivatedEvent(tenantId, agentId));
        return saved;
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_ALREADY_REGISTERED", "Ese correo ya está registrado");
    }
}
