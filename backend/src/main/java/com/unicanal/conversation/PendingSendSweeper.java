package com.unicanal.conversation;

import com.unicanal.channel.ChannelSendErrors;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Message;
import com.unicanal.message.MessageStatusChangedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Cierra los envíos huérfanos: mensajes que llevan más de {@link #MAX_PENDING} en PENDING porque el proceso se
 * cayó (o se reinició) entre guardarlos y recibir la respuesta del canal. Pasan a FAILED con el código
 * CHANNEL_SEND_UNCONFIRMED: no sabemos si el canal llegó a enviarlos, así que la interfaz pide confirmación
 * antes de reintentar. Un envío normal tarda segundos (timeouts de 5 s + 10 s), muy por debajo del límite.
 *
 * <p>La antigüedad se mide con {@code pendingSince} (cuándo entró en PENDING), no con la fecha del mensaje: un
 * reintento de un mensaje antiguo vuelve a PENDING "ahora" y no debe cerrarse mientras se envía. Cada mensaje se
 * cierra con una actualización condicional (solo si sigue PENDING desde antes del límite), así que no pisa un
 * envío que termine justo a la vez.
 */
@Service
public class PendingSendSweeper {

    public static final Duration MAX_PENDING = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(PendingSendSweeper.class);
    private static final int BATCH = 200;

    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PendingSendSweeper(MongoTemplate mongoTemplate, ApplicationEventPublisher events, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.pending-sweep.check-ms:60000}")
    public void closeOrphanedSends() {
        int closed = sweep(clock.instant());
        if (closed > 0) {
            log.warn("Se cerraron {} envíos que quedaron en PENDING más de {} min (sin confirmar)", closed,
                    MAX_PENDING.toMinutes());
        }
    }

    /** Devuelve cuántos mensajes pasó a FAILED. */
    int sweep(Instant now) {
        Instant limit = now.minus(MAX_PENDING);
        Criteria stale = Criteria.where("deliveryStatus").is(DeliveryStatus.PENDING).and("pendingSince").lte(limit);
        int closed = 0;
        while (true) {
            Query candidates = Query.query(stale).limit(BATCH);
            candidates.fields().include("_id");
            List<String> ids = mongoTemplate.find(candidates, Message.class).stream().map(Message::getId).toList();
            if (ids.isEmpty()) {
                return closed;
            }
            for (String id : ids) {
                Message failed = mongoTemplate.findAndModify(
                        Query.query(Criteria.where("_id").is(id).and("deliveryStatus").is(DeliveryStatus.PENDING)
                                .and("pendingSince").lte(limit)),
                        Update.update("deliveryStatus", DeliveryStatus.FAILED)
                                .set("failureReason", ChannelSendErrors.UNCONFIRMED_REASON)
                                .set("failureCode", ChannelSendErrors.UNCONFIRMED)
                                .unset("pendingSince"),
                        FindAndModifyOptions.options().returnNew(true), Message.class);
                if (failed != null) {
                    closed++;
                    // CONVERSATION_UPDATED con el mensaje, como cualquier cambio de estado de entrega
                    events.publishEvent(new MessageStatusChangedEvent(failed.getTenantId(), failed.getConversationId(),
                            failed.getId(), DeliveryStatus.FAILED));
                }
            }
            if (ids.size() < BATCH) {
                return closed;
            }
        }
    }
}
