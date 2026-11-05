package com.unicanal.tenant;

import com.unicanal.ai.AiMode;

/** Configuración por empresa. */
public class TenantSettings {

    public static final int DEFAULT_RISK_ALERT_MINUTES = 15;
    public static final int BUSINESS_INFO_MAX_LENGTH = 4000;

    /** Minutos sin responder a un cliente antes de marcar el chat "en riesgo" (Paso 7). */
    private int riskAlertMinutes = DEFAULT_RISK_ALERT_MINUTES;
    /** Modo de la IA; las empresas existentes (sin el campo) quedan con la IA apagada. */
    private AiMode aiMode = AiMode.OFF;
    /**
     * "Información del negocio": horarios, envíos, medios de pago y productos. Junto con las respuestas rápidas,
     * es lo ÚNICO que la IA puede usar para responder.
     */
    private String businessInfo = "";

    public int getRiskAlertMinutes() {
        return riskAlertMinutes;
    }

    public void setRiskAlertMinutes(int riskAlertMinutes) {
        this.riskAlertMinutes = riskAlertMinutes;
    }

    public AiMode getAiMode() {
        return aiMode != null ? aiMode : AiMode.OFF;
    }

    public void setAiMode(AiMode aiMode) {
        this.aiMode = aiMode;
    }

    public String getBusinessInfo() {
        return businessInfo != null ? businessInfo : "";
    }

    public void setBusinessInfo(String businessInfo) {
        this.businessInfo = businessInfo;
    }
}
