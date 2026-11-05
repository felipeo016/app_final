package com.unicanal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.MutableClock;
import com.unicanal.MutableClockConfig;
import com.unicanal.TestcontainersConfig;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageStatusChangedEvent;
import com.unicanal.message.MessageType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Envíos huérfanos en PENDING, con un reloj controlado y la tarea invocada a mano (sin esperas reales). */
@SpringBootTest
@RecordApplicationEvents
@Import({TestcontainersConfig.class, MutableClockConfig.class})
class PendingSendSweeperIntegrationTest {

    @Autowired
    private PendingSendSweeper sweeper;
    @Autowired
    private MessageRepository messages;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ApplicationEvents events;

    private Instant t0;
    private String tenantId;

    @BeforeEach
    void setUp() {
        t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        tenantId = "tenant-" + UUID.randomUUID();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private Message outbound(Instant timestamp, DeliveryStatus status) {
        return messages.save(new Message(tenantId, "conv-" + UUID.randomUUID(), Direction.OUTBOUND, MessageType.TEXT,
                "hola", null, "agente", timestamp, status));
    }

    private Message reload(Message message) {
        return messages.findById(message.getId()).orElseThrow();
    }

    private void runAt(Instant now) {
        clock.set(now);
        events.clear();
        sweeper.closeOrphanedSends();
    }

    private long failedEventsFor(Message message) {
        return events.stream(MessageStatusChangedEvent.class)
                .filter(event -> event.messageId().equals(message.getId())
                        && event.deliveryStatus() == DeliveryStatus.FAILED)
                .count();
    }

    @Test
    void aSendStuckInPendingForMoreThanFiveMinutesBecomesUnconfirmedWithItsEvent() {
        Message stuck = outbound(t0, DeliveryStatus.PENDING);

        runAt(t0.plus(Duration.ofMinutes(5)).minusMillis(1));
        assertThat(reload(stuck).getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(failedEventsFor(stuck)).isZero();

        runAt(t0.plus(Duration.ofMinutes(5)));
        Message closed = reload(stuck);
        assertThat(closed.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(closed.getFailureCode()).isEqualTo("CHANNEL_SEND_UNCONFIRMED");
        assertThat(closed.getFailureReason())
                .isEqualTo("No se pudo confirmar el envío; puede que el cliente sí lo haya recibido");
        assertThat(failedEventsFor(stuck)).isEqualTo(1);
        Document raw = mongoTemplate.getCollection("messages").find(new Document("_id", stuck.getId())).first();
        assertThat(raw).doesNotContainKey("pendingSince");

        // Una segunda pasada no lo vuelve a tocar ni a notificar
        runAt(t0.plus(Duration.ofMinutes(30)));
        assertThat(failedEventsFor(stuck)).isZero();
    }

    @Test
    void aRetryOfAnOldMessageIsMeasuredFromWhenItWentBackToPending() {
        // Mensaje de hace un día que se está reintentando "ahora" (hace 1 min)
        Message retrying = outbound(t0.minus(Duration.ofDays(1)), DeliveryStatus.PENDING);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(retrying.getId())),
                Update.update("pendingSince", t0.minus(Duration.ofMinutes(1))), Message.class);

        runAt(t0);

        assertThat(reload(retrying).getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(failedEventsFor(retrying)).isZero();
    }

    @Test
    void onlyStalePendingMessagesAreClosed() {
        Message fresh = outbound(t0.minus(Duration.ofMinutes(2)), DeliveryStatus.PENDING);
        Message sent = outbound(t0.minus(Duration.ofHours(2)), DeliveryStatus.SENT);
        Message failed = outbound(t0.minus(Duration.ofHours(2)), DeliveryStatus.FAILED);
        Message inbound = messages.save(new Message(tenantId, "c", Direction.INBOUND, MessageType.TEXT, "hola", null,
                null, t0.minus(Duration.ofHours(2)), null));
        Message stale = outbound(t0.minus(Duration.ofMinutes(6)), DeliveryStatus.PENDING);

        runAt(t0);

        assertThat(reload(stale).getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(reload(fresh).getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(reload(sent).getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reload(failed).getFailureCode()).isNull();
        assertThat(reload(inbound).getDeliveryStatus()).isNull();
        assertThat(events.stream(MessageStatusChangedEvent.class)
                .filter(event -> event.tenantId().equals(tenantId)).count()).isEqualTo(1);
    }
}
