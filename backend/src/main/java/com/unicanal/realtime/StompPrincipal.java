package com.unicanal.realtime;

import com.unicanal.common.AuthPrincipal;
import java.security.Principal;

/** Usuario de una sesión WebSocket, validado en el CONNECT. */
public record StompPrincipal(AuthPrincipal auth) implements Principal {

    @Override
    public String getName() {
        return auth.userId();
    }
}
