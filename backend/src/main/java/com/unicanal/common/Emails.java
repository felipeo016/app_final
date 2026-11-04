package com.unicanal.common;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** Forma canónica de un correo: sin espacios y en minúsculas. */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
