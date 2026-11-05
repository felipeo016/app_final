package com.unicanal.auth;

import com.unicanal.config.AppProperties;
import com.unicanal.user.User;
import java.time.Clock;

/** Emite tokens con otra duración que la configurada, firmados con el mismo secreto de la aplicación. */
public final class JwtTestTokens {

    private JwtTestTokens() {
    }

    public static JwtService.IssuedToken issue(AppProperties appProperties, long expirationMinutes, User user) {
        AppProperties custom = new AppProperties(
                new AppProperties.Jwt(appProperties.jwt().secret(), expirationMinutes));
        return new JwtService(custom, Clock.systemUTC()).issue(user);
    }
}
