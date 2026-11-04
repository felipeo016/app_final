package com.unicanal.auth.dto;

import com.unicanal.tenant.dto.TenantResponse;
import com.unicanal.user.dto.UserResponse;

public record MeResponse(UserResponse user, TenantResponse tenant) {
}
