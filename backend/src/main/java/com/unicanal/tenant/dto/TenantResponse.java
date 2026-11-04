package com.unicanal.tenant.dto;

import com.unicanal.tenant.Tenant;

public record TenantResponse(String id, String name) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(tenant.getId(), tenant.getName());
    }
}
