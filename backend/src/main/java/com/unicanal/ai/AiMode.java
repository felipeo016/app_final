package com.unicanal.ai;

/** Modo de la IA de una empresa. */
public enum AiMode {
    /** Sin IA. */
    OFF,
    /** Botón "Sugerir respuesta": la IA propone y el agente revisa y envía. */
    SUGGEST,
    /** La IA responde sola mientras ningún humano haya respondido el chat (y también se puede sugerir). */
    AUTO
}
