package com.unicanal.channel;

import java.util.Optional;

/**
 * Decide a qué empresa pertenece un evento de webhook a partir de la cuenta del canal. Un webhook no
 * trae JWT, así que esta es la única vía para conocer el tenant. Vacío = evento ajeno, se ignora.
 * Punto de extensión: hoy hay una sola cuenta por instalación; mañana puede consultar una tabla
 * cuenta -> tenant.
 */
public interface TenantResolver {

    Optional<String> resolve(ChannelType channel, String accountId);
}
