package com.unicanal.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndTenantId(String id, String tenantId);

    List<User> findByTenantIdAndRoleOrderByCreatedAtAsc(String tenantId, Role role);
}
