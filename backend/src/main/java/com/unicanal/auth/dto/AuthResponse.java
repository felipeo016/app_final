package com.unicanal.auth.dto;

import com.unicanal.tenant.dto.TenantResponse;
import com.unicanal.user.dto.UserResponse;
import java.time.Instant;

public record AuthResponse(String token, Instant expiresAt, UserResponse user, TenantResponse tenant) {
}
