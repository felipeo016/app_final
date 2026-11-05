package com.unicanal.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndTenantId(String id, String tenantId);

    List<User> findByTenantIdAndRoleOrderByCreatedAtAsc(String tenantId, Role role);

    /** Agentes activos en orden de alta: base estable para la asignación por turnos. */
    List<User> findByTenantIdAndRoleAndActiveTrueOrderByCreatedAtAsc(String tenantId, Role role);

    /** Usuarios activos de la empresa (OWNER incluido): a quiénes se puede asignar un chat. */
    List<User> findByTenantIdAndActiveTrueOrderByNameAsc(String tenantId);
}
