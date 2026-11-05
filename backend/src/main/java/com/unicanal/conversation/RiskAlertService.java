package com.unicanal.conversation;

import com.unicanal.tenant.Tenant;
import com.unicanal.tenant.TenantSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Alerta "cero mensajes perdidos": marca en riesgo las conversaciones abiertas cuyo cliente lleva más de
 * {@code riskAlertMinutes} (configurable por empresa) esperando respuesta.
 *
 * <p>No carga conversaciones en memoria: por cada empresa hace UNA actualización masiva que solo toca las
 * que pasan de {@code atRisk = false} a {@code true}, fijando {@code atRiskSince = ahora} y un id de pasada.
 * Con ese id se recuperan después solo los ids de las que cambiaron, y se publica CONVERSATION_UPDATED
 * únicamente para ellas. Las que ya estaban en riesgo no se tocan ni se vuelven a notificar.
 *
 * <p>Se limpia ({@link #clearRisk()}) al responder con éxito y al resolver. La configuración de cada
 * empresa se lee en cada pasada, así que un cambio de {@code riskAlertMinutes} aplica en la siguiente.
 */
@Service
public class RiskAlertService {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertService.class);

    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RiskAlertService(MongoTemplate mongoTemplate, ApplicationEventPublisher events, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.clock = clock;
    }

    /** Actualización que quita la alerta. */
    public static Update clearRisk() {
        return Update.update("atRisk", false).unset("atRiskSince").unset("atRiskRunId");
    }

    @Scheduled(fixedDelayString = "${app.risk-alert.check-ms:60000}")
    public void checkAllTenants() {
        Instant now = clock.instant();
        Query tenants = new Query();
        tenants.fields().include("_id").include("settings");
        int marked = 0;
        for (Tenant tenant : mongoTemplate.find(tenants, Tenant.class)) {
            try {
                marked += checkTenant(tenant.getId(), minutesOf(tenant), now);
            } catch (RuntimeException e) {
                // Una empresa con problemas no detiene la alerta de las demás
                log.error("Falló la alerta de chats en riesgo del tenant {}", tenant.getId(), e);
            }
        }
        if (marked > 0) {
            log.info("Alerta de riesgo: {} conversaciones nuevas en riesgo", marked);
        }
    }

    /** Marca las conversaciones de la empresa que acaban de entrar en riesgo. Devuelve cuántas. */
    int checkTenant(String tenantId, int riskAlertMinutes, Instant now) {
        Instant threshold = now.minus(Duration.ofMinutes(riskAlertMinutes));
        String runId = UUID.randomUUID().toString();
        Criteria waiting = Criteria.where("tenantId").is(tenantId)
                .and("open").is(true)
                .and("atRisk").ne(true)
                .and("lastInboundAt").lte(threshold)
                // Sin respuesta enviada después del último mensaje del cliente (ausente < cualquier fecha)
                .andOperator(Criteria.expr(ComparisonOperators.valueOf("lastInboundAt")
                        .greaterThan("lastOutboundAt")));
        long modified = mongoTemplate.updateMulti(Query.query(waiting),
                Update.update("atRisk", true).set("atRiskSince", now).set("atRiskRunId", runId),
                Conversation.class).getModifiedCount();
        if (modified == 0) {
            return 0;
        }
        Query changed = Query.query(Criteria.where("tenantId").is(tenantId).and("atRiskRunId").is(runId));
        changed.fields().include("_id");
        List<String> ids = mongoTemplate.find(changed, Conversation.class).stream().map(Conversation::getId).toList();
        ids.forEach(id -> events.publishEvent(new ConversationUpdatedEvent(tenantId, id)));
        return ids.size();
    }

    private static int minutesOf(Tenant tenant) {
        TenantSettings settings = tenant.getSettings();
        int minutes = settings != null ? settings.getRiskAlertMinutes() : TenantSettings.DEFAULT_RISK_ALERT_MINUTES;
        return minutes > 0 ? minutes : TenantSettings.DEFAULT_RISK_ALERT_MINUTES;
    }
}
