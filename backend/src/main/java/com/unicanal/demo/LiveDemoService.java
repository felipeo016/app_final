package com.unicanal.demo;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.channel.InboundResult;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.demo.DemoCustomers.Customer;
import com.unicanal.demo.LiveDemoScript.Kind;
import com.unicanal.demo.LiveDemoScript.Line;
import com.unicanal.message.MessageType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Demo en vivo (solo perfil dev): mientras está activa, cada 20 a 40 segundos (al azar) escribe un cliente en la
 * empresa que la inició, siguiendo el {@link LiveDemoScript}. Corre en el backend, así que sigue aunque se cambie
 * de página o se cierre la pestaña; se detiene con "Detener", al restaurar los datos demo o al apagar el backend.
 *
 * <ul>
 *   <li><b>Una sola demo por empresa</b>: iniciar es idempotente (si ya hay una activa, no se crea otro
 *       temporizador) y detener cancela el único que existe.</li>
 *   <li><b>La posición del guion avanza de forma atómica</b> y NO se reinicia al detener e iniciar: se recorren
 *       todos los mensajes en orden y, al terminar, se vuelve a empezar. Solo "Restaurar datos demo" la reinicia.</li>
 *   <li><b>Personas distintas en cada demo</b>: los clientes nuevos se generan al azar ({@link DemoCustomers}) sin
 *       repetir ningún nombre que ya exista en la bandeja; las continuaciones las escribe un cliente que la demo
 *       ya abrió y cuya conversación sigue abierta.</li>
 * </ul>
 * Los mensajes entran por el mismo {@link InboundMessageProcessor} que un webhook: llegan a la bandeja en tiempo
 * real, se asignan por turnos y cuentan para la alerta de riesgo, sin llamar a ningún canal real.
 */
@Service
@Profile("dev")
public class LiveDemoService {

    private static final Logger log = LoggerFactory.getLogger(LiveDemoService.class);
    private static final String ACCOUNT = "demo-en-vivo";
    private static final List<ChannelType> CHANNELS = List.of(ChannelType.WHATSAPP, ChannelType.INSTAGRAM,
            ChannelType.FACEBOOK);

    private final InboundMessageProcessor processor;
    private final DemoScheduler scheduler;
    private final ContactRepository contacts;
    private final ConversationRepository conversations;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final Duration minDelay;
    private final Duration maxDelay;
    private final Random random = new Random();
    /** Una demo como máximo por empresa. */
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    /** Posición del guion por empresa: sobrevive a detener e iniciar (solo "Restaurar" la reinicia). */
    private final Map<String, AtomicInteger> positions = new ConcurrentHashMap<>();

    public LiveDemoService(InboundMessageProcessor processor, DemoScheduler scheduler, ContactRepository contacts,
            ConversationRepository conversations, MongoTemplate mongoTemplate, Clock clock,
            @Value("${app.live-demo.min-delay-seconds:20}") long minDelaySeconds,
            @Value("${app.live-demo.max-delay-seconds:40}") long maxDelaySeconds) {
        this.processor = processor;
        this.scheduler = scheduler;
        this.contacts = contacts;
        this.conversations = conversations;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.minDelay = Duration.ofSeconds(minDelaySeconds);
        this.maxDelay = Duration.ofSeconds(Math.max(minDelaySeconds, maxDelaySeconds));
    }

    /**
     * Estado visible en el Simulador.
     *
     * @param currentStep  número (1..scriptLength) del último mensaje del guion enviado; 0 si aún no hay ninguno
     * @param lastCustomer quién escribió el último mensaje
     */
    public record Status(boolean running, int messagesSent, Instant nextMessageAt, int scriptLength, int currentStep,
            String lastCustomer) {
    }

    /** Cliente que la demo abrió en esta ejecución (para las continuaciones). */
    private record DemoCustomer(Customer customer, String contactId) {
    }

    /** Una ejecución de la demo. Sus métodos están sincronizados: el hilo de la demo y las peticiones la tocan. */
    private final class Run {
        private final String tenantId;
        private final int channelOffset = random.nextInt(CHANNELS.size());
        private final List<DemoCustomer> customers = new ArrayList<>();
        private final Set<String> usedNames = new HashSet<>();
        private int newCustomers;
        private int sent;
        private int currentStep;
        private String lastCustomer;
        private boolean stopped;
        private DemoScheduler.Scheduled pending;
        private Instant nextAt;

        Run(String tenantId) {
            this.tenantId = tenantId;
        }

        synchronized void scheduleNext() {
            if (stopped) {
                return;
            }
            Duration delay = randomDelay();
            nextAt = clock.instant().plus(delay);
            pending = scheduler.schedule(this::tick, delay);
        }

        /** Un mensaje del guion. Sincronizado entero: así dos disparos nunca toman la misma posición. */
        synchronized void tick() {
            if (stopped) {
                return;
            }
            int position = positions.computeIfAbsent(tenantId, id -> new AtomicInteger()).getAndIncrement();
            int index = Math.floorMod(position, LiveDemoScript.size());
            Line line = LiveDemoScript.LINES.get(index);
            try {
                DemoCustomer who = line.kind() == Kind.FOLLOW_UP ? openCustomer() : null;
                Customer customer = who != null ? who.customer() : newCustomer();
                InboundResult result = processor.process(tenantId, new InboundMessage(customer.channel(), ACCOUNT,
                        customer.externalId(), customer.name(), "demo-live-" + UUID.randomUUID(), MessageType.TEXT,
                        line.text(), clock.instant()));
                if (who == null) {
                    customers.add(new DemoCustomer(customer, result.contactId()));
                }
                sent++;
                currentStep = index + 1;
                lastCustomer = customer.name();
            } catch (RuntimeException e) {
                // Un fallo puntual (p. ej. Mongo reiniciándose) no detiene la demo: sigue con el próximo mensaje
                log.warn("Demo en vivo: no se pudo entregar el mensaje {} del guion", index + 1, e);
            }
            scheduleNext();
        }

        /** Un cliente de esta demo con la conversación aún abierta (al azar); nulo si no queda ninguno. */
        private DemoCustomer openCustomer() {
            List<DemoCustomer> open = customers.stream()
                    .filter(c -> c.contactId() != null && conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(
                            tenantId, c.contactId(), c.customer().channel()).isPresent())
                    .toList();
            return open.isEmpty() ? null : open.get(random.nextInt(open.size()));
        }

        private Customer newCustomer() {
            Set<String> taken = new HashSet<>(mongoTemplate.findDistinct(
                    Query.query(Criteria.where("tenantId").is(tenantId)), "name", Contact.class, String.class));
            taken.addAll(usedNames);
            ChannelType channel = CHANNELS.get((channelOffset + newCustomers) % CHANNELS.size());
            Customer customer = DemoCustomers.newCustomer(channel, taken,
                    externalId -> contacts.findByTenantIdAndIdentityKey(tenantId,
                            Contact.identityKey(channel, externalId)).isPresent(),
                    random);
            usedNames.add(customer.name());
            newCustomers++;
            return customer;
        }

        synchronized void stop() {
            stopped = true;
            nextAt = null;
            if (pending != null) {
                pending.cancel();
            }
        }

        synchronized Status status() {
            return new Status(!stopped, sent, stopped ? null : nextAt, LiveDemoScript.size(), currentStep,
                    lastCustomer);
        }
    }

    /** Inicia la demo de la empresa. Idempotente: si ya hay una activa, devuelve su estado sin crear otra. */
    public Status start(String tenantId) {
        Run run = runs.computeIfAbsent(tenantId, id -> {
            Run created = new Run(id);
            created.scheduleNext();
            log.info("Demo en vivo iniciada para el tenant {} (sigue en el mensaje {} del guion)", id,
                    Math.floorMod(position(id), LiveDemoScript.size()) + 1);
            return created;
        });
        return run.status();
    }

    /** Detiene la demo de la empresa (cancela su único temporizador). Devuelve true si estaba activa. */
    public boolean stop(String tenantId) {
        Run run = runs.remove(tenantId);
        if (run == null) {
            return false;
        }
        run.stop();
        log.info("Demo en vivo detenida para el tenant {}", tenantId);
        return true;
    }

    /** "Restaurar datos demo": la próxima demo vuelve a empezar por el primer mensaje del guion. */
    public void resetScript(String tenantId) {
        positions.remove(tenantId);
    }

    public Status status(String tenantId) {
        Run run = runs.get(tenantId);
        return run != null ? run.status() : new Status(false, 0, null, LiveDemoScript.size(), 0, null);
    }

    private int position(String tenantId) {
        AtomicInteger position = positions.get(tenantId);
        return position == null ? 0 : position.get();
    }

    /** Espera aleatoria entre los límites configurados (20 a 40 s por defecto), con precisión de milisegundos. */
    Duration randomDelay() {
        long min = minDelay.toMillis();
        long max = maxDelay.toMillis();
        return Duration.ofMillis(min == max ? min : min + (long) (random.nextDouble() * (max - min + 1)));
    }
}
