package com.unicanal.common;

import com.unicanal.user.Role;

/** Identidad autenticada de la petición. tenantId y userId vienen del JWT, nunca del cliente. */
public record AuthPrincipal(String userId, String tenantId, Role role) {
}
