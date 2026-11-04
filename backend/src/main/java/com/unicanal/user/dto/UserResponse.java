package com.unicanal.user.dto;

import com.unicanal.user.Role;
import com.unicanal.user.User;
import java.time.Instant;

public record UserResponse(String id, String tenantId, String name, String email, Role role, boolean active,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getTenantId(), user.getName(), user.getEmail(), user.getRole(),
                user.isActive(), user.getCreatedAt());
    }
}
