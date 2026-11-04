package com.unicanal.channel;

import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageStatusChangedEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Aplica los `statuses` de un canal al mensaje saliente correspondiente, buscándolo por
 * {tenantId, externalId}. El estado solo avanza (PENDING → SENT → DELIVERED → READ): los canales
 * pueden entregar los estados fuera de orden y un "delivered" tardío no debe pisar un "read".
 */
@Service
public class DeliveryStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusUpdater.class);

    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;

    public DeliveryStatusUpdater(MongoTemplate mongoTemplate, ApplicationEventPublisher events) {
        this.mongoTemplate = mongoTemplate;
        this.events = events;
    }

    /** Devuelve true si el mensaje existía y su estado cambió. */
    public boolean apply(String tenantId, StatusUpdate update) {
        if (update.externalMessageId() == null) {
            return false;
        }
        List<DeliveryStatus> replaceable = replaceableStatuses(update.status());
        Criteria current = new Criteria().orOperator(
                Criteria.where("deliveryStatus").in(replaceable),
                Criteria.where("deliveryStatus").is(null));
        Query query = Query.query(Criteria.where("tenantId").is(tenantId)
                .and("externalId").is(update.externalMessageId())
                .and("direction").is(Direction.OUTBOUND)
                .andOperator(current));

        Message changed = mongoTemplate.findAndModify(query, Update.update("deliveryStatus", update.status()),
                FindAndModifyOptions.options().returnNew(true), Message.class);
        if (changed == null) {
            // Mensaje desconocido (p. ej. el estado llegó antes de guardar el id externo) o estado que no avanza.
            log.debug("Estado {} ignorado para el mensaje externo {} (tenant {})", update.status(),
                    update.externalMessageId(), tenantId);
            return false;
        }
        events.publishEvent(new MessageStatusChangedEvent(tenantId, changed.getConversationId(), changed.getId(),
                update.status()));
        return true;
    }

    /** Estados actuales a los que el nuevo estado puede sustituir (el nulo se contempla aparte). */
    static List<DeliveryStatus> replaceableStatuses(DeliveryStatus target) {
        return switch (target) {
            case PENDING -> List.of();
            case SENT -> List.of(DeliveryStatus.PENDING);
            case DELIVERED -> List.of(DeliveryStatus.PENDING, DeliveryStatus.SENT);
            case READ -> List.of(DeliveryStatus.PENDING, DeliveryStatus.SENT, DeliveryStatus.DELIVERED);
            // Un fallo solo es posible antes de la entrega.
            case FAILED -> List.of(DeliveryStatus.PENDING, DeliveryStatus.SENT);
        };
    }
}
