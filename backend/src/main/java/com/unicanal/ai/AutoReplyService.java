package com.unicanal.ai;

import com.unicanal.ai.AiAssistant.Draft;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.conversation.ReplyService;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageReceivedEvent;
import com.unicanal.message.MessageType;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Modo "Respuesta automática". Cuando escribe un cliente:
 * <ol>
 *   <li>La recepción solo ENCOLA una tarea en los hilos de la IA y sigue: nunca espera al modelo. Si Ollama está
 *       caído o tarda, el mensaje ya quedó guardado y en la bandeja como siempre.</li>
 *   <li>La tarea responde solo si la empresa tiene el modo AUTO, el chat sigue abierto, el último mensaje es del
 *       cliente y <b>ningún humano ha respondido todavía</b> en ese chat.</li>
 *   <li>Responde por SELECCIÓN, no por redacción ({@link AiAssistant#selectReply}): el modelo elige una respuesta
 *       rápida y se envía su texto exacto con un saludo fijo. Si elige ninguna, un código que no existe, el JSON es
 *       inválido o el modelo falla, no responde: el chat queda para un humano.</li>
 *   <li>Avisa "La IA está escribiendo…", espera de 2 a 4 s al azar y vuelve a comprobar todo (un humano pudo
 *       responder o el cliente pudo escribir otra cosa mientras tanto) antes de enviar.</li>
 * </ol>
 * Una sola tarea a la vez por conversación: si el cliente escribe varias veces seguidas, se vuelve a evaluar al
 * terminar, con todo el contexto.
 */
@Service
public class AutoReplyService {

    private static final Logger log = LoggerFactory.getLogger(AutoReplyService.class);

    private final AiAssistant assistant;
    private final ReplyService replies;
    private final ConversationRepository conversations;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;
    private final AiProperties properties;
    private final Random random = new Random();
    /** Pocos hilos (el modelo atiende de a uno) y cola acotada: si se llena, ese chat queda para un humano. */
    private final ThreadPoolExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> rerun = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingTasks = new AtomicInteger();

    public AutoReplyService(AiAssistant assistant, ReplyService replies, ConversationRepository conversations,
            MongoTemplate mongoTemplate, ApplicationEventPublisher events, AiProperties properties) {
        this.assistant = assistant;
        this.replies = replies;
        this.conversations = conversations;
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.properties = properties;
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100),
                task -> {
                    Thread thread = new Thread(task, "ia-" + threads.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @EventListener
    public void onMessageReceived(MessageReceivedEvent event) {
        submit(event.tenantId(), event.conversationId());
    }

    private void submit(String tenantId, String conversationId) {
        String key = tenantId + ":" + conversationId;
        if (!inFlight.add(key)) {
            // Ya se está atendiendo: se vuelve a evaluar al terminar (con el mensaje nuevo en el contexto)
            rerun.add(key);
            return;
        }
        pendingTasks.incrementAndGet();
        try {
            executor.execute(() -> run(tenantId, conversationId, key));
        } catch (RejectedExecutionException e) {
            inFlight.remove(key);
            pendingTasks.decrementAndGet();
            log.warn("IA: demasiados mensajes en espera; la conversación {} queda para atención humana",
                    conversationId);
        }
    }

    private void run(String tenantId, String conversationId, String key) {
        try {
            attempt(tenantId, conversationId);
        } catch (AiUnavailableException e) {
            log.warn("IA no disponible; la conversación {} queda para atención humana: {}", conversationId,
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("IA: no se pudo responder automáticamente la conversación {}", conversationId, e);
        } finally {
            inFlight.remove(key);
            if (rerun.remove(key)) {
                submit(tenantId, conversationId);
            }
            pendingTasks.decrementAndGet();
        }
    }

    private void attempt(String tenantId, String conversationId) throws InterruptedException {
        if (!autoMode(tenantId) || !waitingForAutomaticReply(tenantId, conversationId)) {
            return;
        }
        AiAssistant.Selection selection = assistant.selectReply(tenantId, conversationId);
        Draft draft = selection.draft();
        AiAnswer answer = draft.answer().orElse(null);
        if (answer == null || !answer.canAnswer()) {
            log.info("IA: la conversación {} queda para un humano ({})", conversationId, selection.outcome());
            return;
        }
        log.info("IA: responde la conversación {} con la {}", conversationId, selection.outcome());
        events.publishEvent(new AiTypingEvent(tenantId, conversationId, true));
        boolean sent = false;
        try {
            Thread.sleep(typingDelay());
            // Durante la espera pudo responder un humano, escribir el cliente otra vez o apagarse la IA
            if (autoMode(tenantId) && waitingForAutomaticReply(tenantId, conversationId)
                    && Objects.equals(lastInboundId(tenantId, conversationId), draft.lastInboundId())) {
                replies.sendAutomatic(tenantId, conversationId, answer.text());
                sent = true;
            }
        } finally {
            if (!sent) {
                events.publishEvent(new AiTypingEvent(tenantId, conversationId, false));
            }
        }
    }

    private boolean autoMode(String tenantId) {
        return assistant.settings(tenantId).getAiMode() == AiMode.AUTO;
    }

    /** Abierto, el último mensaje es del cliente y ningún humano ha escrito (ni intentado escribir) al cliente. */
    boolean waitingForAutomaticReply(String tenantId, String conversationId) {
        Conversation conversation = conversations.findByIdAndTenantId(conversationId, tenantId).orElse(null);
        if (conversation == null || !conversation.isOpen() || conversation.getLastInboundAt() == null) {
            return false;
        }
        if (conversation.getLastOutboundAt() != null
                && !conversation.getLastInboundAt().isAfter(conversation.getLastOutboundAt())) {
            return false;
        }
        boolean humanReplied = mongoTemplate.exists(Query.query(Criteria.where("tenantId").is(tenantId)
                .and("conversationId").is(conversationId).and("direction").is(Direction.OUTBOUND)
                .and("type").is(MessageType.TEXT).and("automatic").ne(true)), Message.class);
        return !humanReplied;
    }

    private String lastInboundId(String tenantId, String conversationId) {
        Message last = mongoTemplate.findOne(Query.query(Criteria.where("tenantId").is(tenantId)
                        .and("conversationId").is(conversationId).and("direction").is(Direction.INBOUND))
                .with(Sort.by(Sort.Direction.DESC, "timestamp")), Message.class);
        return last == null ? null : last.getId();
    }

    private long typingDelay() {
        long min = properties.typingDelayMin().toMillis();
        long max = Math.max(min, properties.typingDelayMax().toMillis());
        return min == max ? min : min + (long) (random.nextDouble() * (max - min + 1));
    }

    /** Para las pruebas: no hay respuestas automáticas en curso ni en cola. */
    boolean idle() {
        return pendingTasks.get() == 0;
    }
}
