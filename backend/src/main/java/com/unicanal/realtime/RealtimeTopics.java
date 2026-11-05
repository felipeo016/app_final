package com.unicanal.realtime;

/** Destinos STOMP. Cada empresa tiene un único tópico; nadie puede suscribirse al de otra. */
public final class RealtimeTopics {

    public static final String ENDPOINT = "/ws";
    public static final String BROKER_PREFIX = "/topic";

    private RealtimeTopics() {
    }

    public static String forTenant(String tenantId) {
        return BROKER_PREFIX + "/tenants/" + tenantId + "/events";
    }
}
