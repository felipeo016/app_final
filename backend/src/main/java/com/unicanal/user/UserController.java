package com.unicanal.user;

import com.unicanal.common.AuthPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Datos de usuarios que cualquier miembro de la empresa puede ver (sin correos ni datos de cuenta). */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /** Usuarios activos de la empresa a los que se puede asignar un chat; el OWNER también es asignable. */
    @GetMapping("/assignable")
    public List<AssignableUser> assignable(@AuthenticationPrincipal AuthPrincipal principal) {
        return users.findByTenantIdAndActiveTrueOrderByNameAsc(principal.tenantId()).stream()
                .map(user -> new AssignableUser(user.getId(), user.getName(), user.getRole()))
                .toList();
    }

    public record AssignableUser(String id, String name, Role role) {
    }
}
