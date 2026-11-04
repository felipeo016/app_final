package com.unicanal.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageReceivedEvent;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.test.util.ReflectionTestUtils;

/** Rutas de carrera y de idempotencia, de forma determinista. La prueba real con hilos está en la de integración. */
@ExtendWith(MockitoExtension.class)
class InboundMessageProcessorTest {

    private static final String TENANT = "t1";
    private static final String KEY = "WHATSAPP:573001112222";

    @Mock
    private ContactRepository contacts;
    @Mock
    private ConversationRepository conversations;
    @Mock
    private MessageRepository messages;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private ApplicationEventPublisher events;

    private InboundMessageProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InboundMessageProcessor(contacts, conversations, messages, mongoTemplate, events);
    }

    private static InboundMessage inbound(String externalId, String contactName) {
        return new InboundMessage(ChannelType.WHATSAPP, "acc", "573001112222", contactName, externalId,
                MessageType.TEXT, "hola", Instant.parse("2026-11-05T10:00:00Z"));
    }

    private static Contact contact(String id, String name) {
        Contact contact = new Contact(TENANT, name, ChannelType.WHATSAPP, "573001112222");
        ReflectionTestUtils.setField(contact, "id", id);
        return contact;
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation(TENANT, "c1", ChannelType.WHATSAPP);
        ReflectionTestUtils.setField(conversation, "id", id);
        return conversation;
    }

    private void stubMessageSave() {
        when(messages.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", "m1");
            return message;
        });
    }

    @Test
    void createsContactConversationAndMessageForANewCustomer() {
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.empty());
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.empty());
        when(contacts.save(any(Contact.class))).thenReturn(contact("c1", "Ana"));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.empty());
        when(conversations.save(any(Conversation.class))).thenReturn(conversation("v1"));
        stubMessageSave();

        InboundResult result = processor.process(TENANT, inbound("wamid.1", "Ana"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.newConversation()).isTrue();
        assertThat(result.conversationId()).isEqualTo("v1");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getDirection()).isEqualTo(Direction.INBOUND);
        assertThat(saved.getValue().getSentBy()).isNull();
        assertThat(saved.getValue().getDeliveryStatus()).isNull();
        assertThat(saved.getValue().getExternalId()).isEqualTo("wamid.1");
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
        verify(events).publishEvent(new MessageReceivedEvent(TENANT, "c1", "v1", "m1", true));
        // contador y marcas de tiempo + vista previa: dos actualizaciones atómicas
        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(UpdateDefinition.class),
                eq(Conversation.class));
    }

    @Test
    void losingTheRaceCreatingTheContactReusesTheExistingOneAndContinues() {
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.empty());
        // 1ª búsqueda: no existe; el insert pierde la carrera; 2ª búsqueda: ya existe
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY))
                .thenReturn(Optional.empty(), Optional.of(contact("c1", "Ana")));
        when(contacts.save(any(Contact.class))).thenThrow(new DuplicateKeyException("dup contacto"));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.of(conversation("v1")));
        stubMessageSave();

        InboundResult result = processor.process(TENANT, inbound("wamid.1", "Ana"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.contactId()).isEqualTo("c1");
        assertThat(result.messageId()).isEqualTo("m1"); // el mensaje NO se pierde
        verify(contacts, times(2)).findByTenantIdAndIdentityKey(TENANT, KEY);
    }

    @Test
    void losingTheRaceCreatingTheConversationReusesTheOpenOneAndContinues() {
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.empty());
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.of(contact("c1", "Ana")));
        // 1ª búsqueda: no hay abierta; el insert pierde la carrera; 2ª búsqueda: ya la creó el otro hilo
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.empty(), Optional.of(conversation("v1")));
        when(conversations.save(any(Conversation.class))).thenThrow(new DuplicateKeyException("dup conversación"));
        stubMessageSave();

        InboundResult result = processor.process(TENANT, inbound("wamid.1", "Ana"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.newConversation()).isFalse(); // la creó el otro hilo
        assertThat(result.conversationId()).isEqualTo("v1");
        assertThat(result.messageId()).isEqualTo("m1");
    }

    @Test
    void createsANewConversationIfTheOtherOneWasResolvedBetweenLookupAndInsert() {
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.empty());
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.of(contact("c1", "Ana")));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.empty());
        when(conversations.save(any(Conversation.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenReturn(conversation("v2"));
        stubMessageSave();

        InboundResult result = processor.process(TENANT, inbound("wamid.1", "Ana"));

        assertThat(result.conversationId()).isEqualTo("v2");
        assertThat(result.newConversation()).isTrue();
    }

    @Test
    void failsLoudlyInsteadOfDroppingTheMessageWhenTheConversationCannotBeObtained() {
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.empty());
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.of(contact("c1", "Ana")));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.empty());
        when(conversations.save(any(Conversation.class))).thenThrow(new DuplicateKeyException("dup"));

        // Se lanza (el webhook responderá 5xx y Meta reintentará); nunca se descarta en silencio.
        assertThatThrownBy(() -> processor.process(TENANT, inbound("wamid.1", "Ana")))
                .isInstanceOf(IllegalStateException.class);
        verify(messages, never()).save(any());
    }

    @Test
    void aRetriedMessageIsRecognizedBeforeTouchingAnything() {
        Message stored = new Message(TENANT, "v1", Direction.INBOUND, MessageType.TEXT, "hola", "wamid.1", null,
                Instant.now(), null);
        ReflectionTestUtils.setField(stored, "id", "m1");
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.of(stored));

        InboundResult result = processor.process(TENANT, inbound("wamid.1", "Ana"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.conversationId()).isEqualTo("v1");
        assertThat(result.messageId()).isEqualTo("m1");
        verify(contacts, never()).save(any());
        verify(messages, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aConcurrentDuplicateDetectedAtInsertIsReportedAsDuplicateWithoutSideEffects() {
        Message winner = new Message(TENANT, "v1", Direction.INBOUND, MessageType.TEXT, "hola", "wamid.1", null,
                Instant.now(), null);
        ReflectionTestUtils.setField(winner, "id", "m9");
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.of(contact("c1", "Ana")));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.of(conversation("v1")));
        when(messages.save(any(Message.class))).thenThrow(new DuplicateKeyException("dup mensaje"));

        InboundResult result = processor.process(TENANT, inbound("wamid.1", "Ana"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.messageId()).isEqualTo("m9");
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(UpdateDefinition.class),
                eq(Conversation.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void adoptsTheProfileNameWhenTheContactIsStillNamedAfterItsPhone() {
        when(messages.findByTenantIdAndExternalId(TENANT, "wamid.1")).thenReturn(Optional.empty());
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY))
                .thenReturn(Optional.of(contact("c1", "573001112222")));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.of(conversation("v1")));
        stubMessageSave();

        processor.process(TENANT, inbound("wamid.1", "Ana Pérez"));

        verify(mongoTemplate).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Contact.class));
    }

    @Test
    void keepsAnExistingRealNameAndIgnoresMissingProfileNames() {
        when(messages.findByTenantIdAndExternalId(eq(TENANT), any())).thenReturn(Optional.empty());
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.of(contact("c1", "Ana")));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.of(conversation("v1")));
        stubMessageSave();

        processor.process(TENANT, inbound("wamid.1", "Otro Nombre"));
        processor.process(TENANT, inbound("wamid.2", null));

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(UpdateDefinition.class),
                eq(Contact.class));
    }

    @Test
    void anInboundWithoutExternalIdIsStoredWithoutIdempotencyCheck() {
        when(contacts.findByTenantIdAndIdentityKey(TENANT, KEY)).thenReturn(Optional.of(contact("c1", "Ana")));
        when(conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, "c1", ChannelType.WHATSAPP))
                .thenReturn(Optional.of(conversation("v1")));
        stubMessageSave();

        InboundResult result = processor.process(TENANT, inbound(null, null));

        assertThat(result.duplicate()).isFalse();
        verify(messages, never()).findByTenantIdAndExternalId(any(), any());
    }
}
