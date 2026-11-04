package com.unicanal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.TestcontainersConfig;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Dos (o más) mensajes de un cliente nuevo que llegan a la vez contra MongoDB real: el que pierde la
 * carrera recibe DuplicateKeyException, vuelve a buscar el existente y continúa. Ningún mensaje se pierde.
 *
 * <p>Las pruebas deterministas envuelven el repositorio real con un {@link Proxy} que, justo después de
 * una búsqueda sin resultados, retiene a los hilos en una barrera hasta que todos han visto "no existe".
 * Así TODOS intentan insertar a la vez y la carrera ocurre siempre (no depende de la suerte). Se cuentan
 * las DuplicateKeyException lanzadas por Mongo para demostrar que el camino de recuperación se ejecutó.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class InboundConcurrencyIntegrationTest {

    private static final String TENANT = "tenant-concurrency";

    @Autowired
    private ContactRepository contacts;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private ApplicationEventPublisher events;

    // --- carrera al crear el contacto (y, a continuación, la conversación) ---

    @Test
    void twoMessagesFromANewContactArrivingTogetherAreBothStored() throws Exception {
        String phone = newPhone();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger duplicateKeys = new AtomicInteger();
        InboundMessageProcessor processor = processor(
                holdAfterEmptyLookup(counting(contacts, ContactRepository.class, duplicateKeys), ContactRepository.class,
                        "findByTenantIdAndIdentityKey", barrier),
                counting(conversations, ConversationRepository.class, duplicateKeys));

        List<InboundResult> results = runConcurrently(
                () -> processor.process(TENANT, inbound(phone, "wamid." + UUID.randomUUID(), "primero")),
                () -> processor.process(TENANT, inbound(phone, "wamid." + UUID.randomUUID(), "segundo")));

        assertThat(duplicateKeys.get()).as("el perdedor de la carrera recibió DuplicateKeyException")
                .isGreaterThanOrEqualTo(1);
        assertThat(results).noneMatch(InboundResult::duplicate);
        assertOneContactOneOpenConversationWithMessages(phone, 2);
        assertThat(results).filteredOn(InboundResult::newConversation).hasSize(1);
    }

    // --- carrera al crear la conversación (el contacto ya existe) ---

    @Test
    void twoMessagesCreatingTheSameConversationTogetherAreBothStored() throws Exception {
        String phone = newPhone();
        contacts.save(new Contact(TENANT, "Ana", ChannelType.WHATSAPP, phone));
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger duplicateKeys = new AtomicInteger();
        InboundMessageProcessor processor = processor(contacts,
                holdAfterEmptyLookup(counting(conversations, ConversationRepository.class, duplicateKeys),
                        ConversationRepository.class, "findByTenantIdAndContactIdAndChannelAndOpenTrue", barrier));

        List<InboundResult> results = runConcurrently(
                () -> processor.process(TENANT, inbound(phone, "wamid." + UUID.randomUUID(), "primero")),
                () -> processor.process(TENANT, inbound(phone, "wamid." + UUID.randomUUID(), "segundo")));

        assertThat(duplicateKeys.get()).as("el perdedor de la carrera recibió DuplicateKeyException")
                .isEqualTo(1);
        assertThat(results).noneMatch(InboundResult::duplicate);
        assertOneContactOneOpenConversationWithMessages(phone, 2);
        assertThat(results).filteredOn(InboundResult::newConversation).hasSize(1);
        assertThat(results).extracting(InboundResult::conversationId).containsOnly(results.get(0).conversationId());
    }

    // --- el canal reintenta el mismo mensaje mientras aún se procesa ---

    @Test
    void theSameMessageDeliveredTwiceAtOnceIsStoredOnlyOnce() throws Exception {
        String phone = newPhone();
        String sameId = "wamid." + UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger duplicateKeys = new AtomicInteger();
        InboundMessageProcessor processor = processor(contacts, conversations,
                holdAfterEmptyLookup(counting(messages, MessageRepository.class, duplicateKeys),
                        MessageRepository.class, "findByTenantIdAndExternalId", barrier));

        List<InboundResult> results = runConcurrently(
                () -> processor.process(TENANT, inbound(phone, sameId, "hola")),
                () -> processor.process(TENANT, inbound(phone, sameId, "hola")));

        assertThat(duplicateKeys.get()).as("el segundo insert chocó con el índice único").isEqualTo(1);
        assertThat(results).filteredOn(InboundResult::duplicate).hasSize(1);
        assertOneContactOneOpenConversationWithMessages(phone, 1);
    }

    // --- prueba de estrés sin barreras: muchos hilos, muchas rondas ---

    @Test
    void manyThreadsRacingOnManyNewContactsNeverLoseAMessageOrDuplicateAnything() throws Exception {
        int threads = 8;
        for (int round = 0; round < 15; round++) {
            String phone = newPhone();
            List<Callable<InboundResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String text = "mensaje " + i;
                tasks.add(() -> new InboundMessageProcessor(contacts, conversations, messages, mongoTemplate, events)
                        .process(TENANT, inbound(phone, "wamid." + UUID.randomUUID(), text)));
            }

            List<InboundResult> results = runConcurrently(tasks.toArray(new Callable[0]));

            assertThat(results).hasSize(threads).noneMatch(InboundResult::duplicate);
            assertOneContactOneOpenConversationWithMessages(phone, threads);
        }
    }

    // --- verificación y utilidades ---

    private void assertOneContactOneOpenConversationWithMessages(String phone, int expectedMessages) {
        long contactCount = mongoTemplate.count(Query.query(Criteria.where("tenantId").is(TENANT)
                .and("identityKeys").is("WHATSAPP:" + phone)), Contact.class);
        assertThat(contactCount).as("contactos con esa identidad").isEqualTo(1);

        Contact contact = contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow();
        List<Conversation> all = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(TENANT, contact.getId());
        assertThat(all).as("conversaciones del contacto").hasSize(1);
        Conversation conversation = all.get(0);
        assertThat(conversation.isOpen()).isTrue();
        assertThat(messages.findByTenantIdAndConversationId(TENANT, conversation.getId(), PageRequest.of(0, 100))
                .getTotalElements()).as("mensajes guardados").isEqualTo(expectedMessages);
        assertThat(conversation.getUnreadCount()).as("no leídos (incrementos atómicos)").isEqualTo(expectedMessages);
    }

    private InboundMessageProcessor processor(ContactRepository contactRepository,
            ConversationRepository conversationRepository) {
        return processor(contactRepository, conversationRepository, messages);
    }

    private InboundMessageProcessor processor(ContactRepository contactRepository,
            ConversationRepository conversationRepository, MessageRepository messageRepository) {
        return new InboundMessageProcessor(contactRepository, conversationRepository, messageRepository,
                mongoTemplate, events);
    }

    private static InboundMessage inbound(String phone, String externalId, String text) {
        return new InboundMessage(ChannelType.WHATSAPP, "acc", phone, "Ana", externalId, MessageType.TEXT, text,
                Instant.now());
    }

    private static String newPhone() {
        return "57" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L);
    }

    @SafeVarargs
    private static <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            CyclicBarrier start = new CyclicBarrier(tasks.length);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS)); // cualquier excepción del hilo falla la prueba
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Cuenta las DuplicateKeyException que lanza el repositorio real (y las vuelve a lanzar). */
    @SuppressWarnings("unchecked")
    private static <T> T counting(T real, Class<T> type, AtomicInteger duplicateKeys) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof DuplicateKeyException) {
                    duplicateKeys.incrementAndGet();
                }
                throw e.getCause();
            }
        });
    }

    /**
     * Tras ejecutar {@code methodName} de verdad, si no encontró nada espera en la barrera a que el otro
     * hilo también haya visto "no existe". Garantiza que ambos intentan crear el recurso a la vez.
     */
    @SuppressWarnings("unchecked")
    private static <T> T holdAfterEmptyLookup(T real, Class<T> type, String methodName, CyclicBarrier barrier) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            Object result;
            try {
                result = method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (method.getName().equals(methodName) && result instanceof Optional<?> optional && optional.isEmpty()) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                    // el otro hilo ya no necesita esperar: se continúa
                }
            }
            return result;
        });
    }
}
