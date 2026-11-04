package com.unicanal.tenant;

/** Configuración por empresa. */
public class TenantSettings {

    public static final int DEFAULT_RISK_ALERT_MINUTES = 15;

    /** Minutos sin responder a un cliente antes de marcar el chat "en riesgo" (Paso 7). */
    private int riskAlertMinutes = DEFAULT_RISK_ALERT_MINUTES;

    public int getRiskAlertMinutes() {
        return riskAlertMinutes;
    }

    public void setRiskAlertMinutes(int riskAlertMinutes) {
        this.riskAlertMinutes = riskAlertMinutes;
    }
}
