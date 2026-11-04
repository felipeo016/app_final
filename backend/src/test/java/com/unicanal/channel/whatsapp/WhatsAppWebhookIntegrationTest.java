package com.unicanal.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unicanal.Fixtures;
import com.unicanal.TestcontainersConfig;
import com.unicanal.channel.ChannelType;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationRepository;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import com.unicanal.tenant.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Webhook de WhatsApp de punta a punta con MongoDB real: firma sobre bytes crudos, verificación GET,
 * idempotencia, varios entries/mensajes/estados, tipos no soportados, nombre de perfil y cuentas ajenas.
 * Las peticiones no llevan JWT ni token CSRF: el webhook debe aceptarlas igualmente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WhatsAppWebhookIntegrationTest {

    private static final String TENANT = "tenant-whatsapp-test"; // = app.whatsapp.default-tenant-id de prueba
    private static final String URL = "/api/webhooks/whatsapp";
    private static final String PHONE_ID = WebhookTestSupport.PHONE_NUMBER_ID;
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private ContactRepository contacts;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private MessageRepository messages;

    @BeforeEach
    void ensureTenant() {
        if (!tenants.existsById(TENANT)) {
            tenants.save(Fixtures.tenant(TENANT, "Empresa WhatsApp de prueba"));
        }
    }

    // --- verificación GET ---

    @Test
    void verificationReturnsTheChallengeAsPlainText() throws Exception {
        mockMvc.perform(get(URL).param("hub.mode", "subscribe").param("hub.verify_token", "test-verify-token")
                        .param("hub.challenge", "1158201444"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("1158201444"));
    }

    @Test
    void verificationIsRejectedWithAWrongTokenModeOrMissingChallenge() throws Exception {
        mockMvc.perform(get(URL).param("hub.mode", "subscribe").param("hub.verify_token", "otro")
                        .param("hub.challenge", "123")).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("hub.mode", "unsubscribe").param("hub.verify_token", "test-verify-token")
                        .param("hub.challenge", "123")).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("hub.mode", "subscribe").param("hub.verify_token", "test-verify-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
    }

    // --- firma ---

    @Test
    void aValidSignatureStoresTheMessageWithoutJwtOrCsrf() throws Exception {
        String phone = newPhone();
        String id = newId();
        String body = WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana Pérez", id, "Hola, quiero comprar");

        signedPost(body).andExpect(status().isOk());

        Contact contact = contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow();
        assertThat(contact.getName()).isEqualTo("Ana Pérez");
        Conversation conversation = conversations
                .findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, contact.getId(), ChannelType.WHATSAPP)
                .orElseThrow();
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.NEW);
        assertThat(conversation.getUnreadCount()).isEqualTo(1);
        assertThat(conversation.getLastMessagePreview()).isEqualTo("Hola, quiero comprar");
        assertThat(conversation.getLastInboundAt()).isEqualTo(Instant.ofEpochSecond(1730000000L));
        assertThat(conversation.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(1730000000L));
        Message message = messages.findByTenantIdAndExternalId(TENANT, id).orElseThrow();
        assertThat(message.getDirection()).isEqualTo(Direction.INBOUND);
        assertThat(message.getType()).isEqualTo(MessageType.TEXT);
        assertThat(message.getContent()).isEqualTo("Hola, quiero comprar");
        assertThat(message.getConversationId()).isEqualTo(conversation.getId());
        assertThat(message.getSentBy()).isNull();
    }

    @Test
    void anInvalidSignatureIsRejectedAndNothingIsStored() throws Exception {
        String phone = newPhone();
        String id = newId();
        String body = WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana", id, "hola");

        mockMvc.perform(rawPost(body, WebhookTestSupport.sign("otro-secreto", bytes(body))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        assertNothingStored(phone, id);
    }

    @Test
    void aMissingOrMalformedSignatureIsRejected() throws Exception {
        String phone = newPhone();
        String id = newId();
        String body = WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana", id, "hola");

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(bytes(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        mockMvc.perform(rawPost(body, "sha256=")).andExpect(status().isForbidden());
        mockMvc.perform(rawPost(body, "no-es-una-firma")).andExpect(status().isForbidden());

        assertNothingStored(phone, id);
    }

    @Test
    void theSignatureIsCheckedOnTheRawBytesNotOnReserializedJson() throws Exception {
        String phone = newPhone();
        String id = newId();
        // JSON válido pero "feo": espacios raros, saltos de línea y escapes \\u. Un servidor que
        // deserializara y volviera a serializar produciría otros bytes y la firma no coincidiría.
        String body = "{ \"object\" : \"whatsapp_business_account\",\n  \"entry\": [ { \"id\":\"w\", \"changes\":[ "
                + "{\"field\":\"messages\", \"value\":{ \"metadata\":{\"phone_number_id\":\"" + PHONE_ID + "\"}, "
                + "\"contacts\":[{\"profile\":{\"name\":\"Jos\\u00e9\"},\"wa_id\":\"" + phone + "\"}], "
                + "\"messages\":[{\"from\":\"" + phone + "\",\"id\":\"" + id + "\",\"timestamp\":\"1730000000\","
                + "\"type\":\"text\",\"text\":{\"body\":\"  hola\\u00a1  \"}}] } } ] } ]   }  \n";
        String signature = WebhookTestSupport.sign(body);

        mockMvc.perform(rawPost(body, signature)).andExpect(status().isOk());
        assertThat(messages.findByTenantIdAndExternalId(TENANT, id)).isPresent();
        assertThat(contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow().getName())
                .isEqualTo("José");

        // El mismo contenido con un espacio de más ya no coincide con esa firma.
        String reformatted = body.replace("\"object\" :", "\"object\"  :");
        String otherId = newId();
        mockMvc.perform(rawPost(reformatted.replace(id, otherId), signature)).andExpect(status().isForbidden());
        assertThat(messages.findByTenantIdAndExternalId(TENANT, otherId)).isEmpty();
    }

    @Test
    void aBodyWithAValidSignatureButInvalidJsonIsDiscardedWith200SoMetaDoesNotRetryForever() throws Exception {
        String body = "esto no es json";
        long before = messages.count();

        // Error permanente: reintentarlo nunca lo arreglaría, así que no se pide reintento (se registra con ERROR).
        mockMvc.perform(rawPost(body, WebhookTestSupport.sign(body))).andExpect(status().isOk());
        assertThat(messages.count()).isEqualTo(before);
    }

    // --- idempotencia ---

    @Test
    void aRetriedWebhookIsProcessedOnlyOnce() throws Exception {
        String phone = newPhone();
        String id = newId();
        String body = WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana", id, "hola");

        signedPost(body).andExpect(status().isOk());
        signedPost(body).andExpect(status().isOk());
        signedPost(body).andExpect(status().isOk());

        Contact contact = contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow();
        List<Conversation> all = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(TENANT, contact.getId());
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getUnreadCount()).isEqualTo(1);
        assertThat(messages.findByTenantIdAndConversationId(TENANT, all.get(0).getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    // --- varios entries, changes, messages y statuses ---

    @Test
    void everyEntryChangeMessageAndStatusInOnePostIsProcessed() throws Exception {
        String p1 = newPhone();
        String p2 = newPhone();
        String p3 = newPhone();
        String m1 = newId();
        String m2 = newId();
        String m3 = newId();
        String m4 = newId();
        String outId = newId();
        seedConversationWithOutbound(newPhone(), outId, DeliveryStatus.PENDING);
        String body = """
                {"object":"whatsapp_business_account","entry":[
                  {"id":"w1","changes":[
                    {"field":"messages","value":{"metadata":{"phone_number_id":"%1$s"},
                      "contacts":[{"profile":{"name":"Uno"},"wa_id":"%2$s"},{"profile":{"name":"Dos"},"wa_id":"%3$s"}],
                      "messages":[{"from":"%2$s","id":"%5$s","timestamp":"1730000001","type":"text","text":{"body":"a"}},
                                  {"from":"%3$s","id":"%6$s","timestamp":"1730000002","type":"text","text":{"body":"b"}}],
                      "statuses":[{"id":"%9$s","status":"delivered","timestamp":"1730000003"}]}},
                    {"field":"otro_campo","value":{"cualquier":"cosa"}},
                    {"field":"messages","value":{"metadata":{"phone_number_id":"%1$s"},
                      "messages":[{"from":"%4$s","id":"%7$s","timestamp":"1730000004","type":"text","text":{"body":"c"}}]}}]},
                  {"id":"w2","changes":[
                    {"field":"messages","value":{"metadata":{"phone_number_id":"%1$s"},
                      "messages":[{"from":"%2$s","id":"%8$s","timestamp":"1730000005","type":"text","text":{"body":"d"}}]}}]}
                ]}""".formatted(PHONE_ID, p1, p2, p3, m1, m2, m3, m4, outId);

        signedPost(body).andExpect(status().isOk());

        assertThat(messages.findByTenantIdAndExternalId(TENANT, m1)).isPresent();
        assertThat(messages.findByTenantIdAndExternalId(TENANT, m2)).isPresent();
        assertThat(messages.findByTenantIdAndExternalId(TENANT, m3)).isPresent();
        assertThat(messages.findByTenantIdAndExternalId(TENANT, m4)).isPresent();
        assertThat(contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + p1).orElseThrow().getName())
                .isEqualTo("Uno");
        assertThat(contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + p2).orElseThrow().getName())
                .isEqualTo("Dos");
        assertThat(contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + p3).orElseThrow().getName())
                .isEqualTo(p3); // sin perfil en el payload: se llama como su teléfono
        // p1 escribió dos veces (en entries distintos): una conversación con 2 no leídos
        Contact first = contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + p1).orElseThrow();
        assertThat(conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(TENANT, first.getId()))
                .singleElement().satisfies(c -> assertThat(c.getUnreadCount()).isEqualTo(2));
        // el estado del mismo POST también se aplicó
        assertThat(messages.findByTenantIdAndExternalId(TENANT, outId).orElseThrow().getDeliveryStatus())
                .isEqualTo(DeliveryStatus.DELIVERED);
    }

    // --- tipos no soportados ---

    @Test
    void unsupportedMessageTypesAreStoredAsInboundWithADescriptiveText() throws Exception {
        String phone = newPhone();
        String audioId = newId();
        String stickerId = newId();
        String reactionId = newId();
        String locationId = newId();
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"w","changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%1$s"},
                  "messages":[
                    {"from":"%2$s","id":"%3$s","timestamp":"1730000001","type":"audio","audio":{"id":"a"}},
                    {"from":"%2$s","id":"%4$s","timestamp":"1730000002","type":"sticker","sticker":{"id":"s"}},
                    {"from":"%2$s","id":"%5$s","timestamp":"1730000003","type":"reaction","reaction":{"message_id":"x","emoji":"❤️"}},
                    {"from":"%2$s","id":"%6$s","timestamp":"1730000004","type":"location","location":{"latitude":4.6,"longitude":-74.1}}
                  ]}}]}]}""".formatted(PHONE_ID, phone, audioId, stickerId, reactionId, locationId);

        signedPost(body).andExpect(status().isOk());

        assertInbound(audioId, "[Audio recibido]");
        assertInbound(stickerId, "[Sticker recibido]");
        assertInbound(reactionId, "[Reacción recibida]: ❤️");
        assertInbound(locationId, "[Ubicación recibida]");
    }

    @Test
    void anImageIsStoredWithTheImageType() throws Exception {
        String phone = newPhone();
        String id = newId();
        String body = WebhookTestSupport.withMessage(PHONE_ID, phone, null,
                "{\"from\":\"" + phone + "\",\"id\":\"" + id + "\",\"timestamp\":\"1730000001\","
                        + "\"type\":\"image\",\"image\":{\"id\":\"img\",\"caption\":\"mi comprobante\"}}");

        signedPost(body).andExpect(status().isOk());

        Message message = messages.findByTenantIdAndExternalId(TENANT, id).orElseThrow();
        assertThat(message.getType()).isEqualTo(MessageType.IMAGE);
        assertThat(message.getDirection()).isEqualTo(Direction.INBOUND);
        assertThat(message.getContent()).isEqualTo("[Imagen recibida]: mi comprobante");
    }

    // --- nombre del perfil ---

    @Test
    void theProfileNameIsAdoptedLaterButNeverOverwritesARealName() throws Exception {
        String phone = newPhone();

        signedPost(WebhookTestSupport.textMessage(PHONE_ID, phone, null, newId(), "uno")).andExpect(status().isOk());
        assertThat(contactName(phone)).isEqualTo(phone);

        signedPost(WebhookTestSupport.textMessage(PHONE_ID, phone, "María López", newId(), "dos"))
                .andExpect(status().isOk());
        assertThat(contactName(phone)).isEqualTo("María López");

        signedPost(WebhookTestSupport.textMessage(PHONE_ID, phone, "Otro Nombre", newId(), "tres"))
                .andExpect(status().isOk());
        assertThat(contactName(phone)).isEqualTo("María López");
    }

    // --- cuentas ajenas ---

    @Test
    void eventsOfAnotherPhoneNumberIdAreIgnoredWithoutError() throws Exception {
        String phone = newPhone();
        String id = newId();
        String body = WebhookTestSupport.textMessage("999999999", phone, "Ana", id, "hola");

        signedPost(body).andExpect(status().isOk());

        assertNothingStored(phone, id);
    }

    @Test
    void ownEventsAreProcessedEvenIfTheSamePostAlsoCarriesForeignOnes() throws Exception {
        String ownPhone = newPhone();
        String foreignPhone = newPhone();
        String ownId = newId();
        String foreignId = newId();
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"w","changes":[
                  {"field":"messages","value":{"metadata":{"phone_number_id":"999999999"},
                    "messages":[{"from":"%3$s","id":"%5$s","timestamp":"1730000001","type":"text","text":{"body":"ajeno"}}]}},
                  {"field":"messages","value":{"metadata":{"phone_number_id":"%1$s"},
                    "messages":[{"from":"%2$s","id":"%4$s","timestamp":"1730000002","type":"text","text":{"body":"propio"}}]}}
                ]}]}""".formatted(PHONE_ID, ownPhone, foreignPhone, ownId, foreignId);

        signedPost(body).andExpect(status().isOk());

        assertThat(messages.findByTenantIdAndExternalId(TENANT, ownId)).isPresent();
        assertNothingStored(foreignPhone, foreignId);
    }

    // --- estados de entrega ---

    @Test
    void statusesMoveTheMessageForwardAndIgnoreLateOrImpossibleOnes() throws Exception {
        String outId = newId();
        seedConversationWithOutbound(newPhone(), outId, DeliveryStatus.PENDING);

        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, outId, "sent")).andExpect(status().isOk());
        assertThat(deliveryStatus(outId)).isEqualTo(DeliveryStatus.SENT);

        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, outId, "read")).andExpect(status().isOk());
        assertThat(deliveryStatus(outId)).isEqualTo(DeliveryStatus.READ);

        // Llegan tarde / fuera de orden: no deben retroceder el estado
        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, outId, "delivered")).andExpect(status().isOk());
        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, outId, "sent")).andExpect(status().isOk());
        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, outId, "failed")).andExpect(status().isOk());
        assertThat(deliveryStatus(outId)).isEqualTo(DeliveryStatus.READ);
    }

    @Test
    void aFailedStatusAppliesWhileTheMessageWasNotDeliveredYet() throws Exception {
        String outId = newId();
        seedConversationWithOutbound(newPhone(), outId, DeliveryStatus.SENT);

        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, outId, "failed")).andExpect(status().isOk());

        assertThat(deliveryStatus(outId)).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void aStatusForAnUnknownMessageOrAnotherAccountChangesNothingAndStillReturns200() throws Exception {
        String outId = newId();
        seedConversationWithOutbound(newPhone(), outId, DeliveryStatus.SENT);

        signedPost(WebhookTestSupport.statusOnly(PHONE_ID, "wamid.NO-EXISTE", "read")).andExpect(status().isOk());
        signedPost(WebhookTestSupport.statusOnly("999999999", outId, "read")).andExpect(status().isOk());

        assertThat(deliveryStatus(outId)).isEqualTo(DeliveryStatus.SENT);
    }

    // --- episodios y orden ---

    @Test
    void aMessageAfterResolvingStartsANewConversationAndKeepsTheOldOne() throws Exception {
        String phone = newPhone();
        signedPost(WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana", newId(), "primer episodio"))
                .andExpect(status().isOk());
        Contact contact = contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow();
        Conversation first = conversations.findByTenantIdAndContactIdAndChannelAndOpenTrue(TENANT, contact.getId(),
                ChannelType.WHATSAPP).orElseThrow();
        first.setStatus(ConversationStatus.RESOLVED);
        conversations.save(first);

        signedPost(WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana", newId(), "segundo episodio"))
                .andExpect(status().isOk());

        List<Conversation> all = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(TENANT, contact.getId());
        assertThat(all).hasSize(2);
        assertThat(all.get(0).isOpen()).isTrue();
        assertThat(all.get(0).getLastMessagePreview()).isEqualTo("segundo episodio");
        assertThat(all.get(1).getId()).isEqualTo(first.getId());
        assertThat(all.get(1).isOpen()).isFalse();
    }

    @Test
    void anOutOfOrderOlderMessageCountsAsUnreadButDoesNotReplaceThePreview() throws Exception {
        String phone = newPhone();
        signedPost(payloadAt(phone, newId(), "1730000100", "más reciente")).andExpect(status().isOk());

        signedPost(payloadAt(phone, newId(), "1730000050", "más antiguo, llegó tarde")).andExpect(status().isOk());

        Contact contact = contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow();
        Conversation conversation = conversations.findByTenantIdAndContactIdOrderByCreatedAtDesc(TENANT,
                contact.getId()).get(0);
        assertThat(conversation.getUnreadCount()).isEqualTo(2);
        assertThat(conversation.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(1730000100L));
        assertThat(conversation.getLastMessagePreview()).isEqualTo("más reciente");
    }

    @Test
    void theSameCustomerInADifferentTenantIsNotMixedUp() throws Exception {
        // El webhook solo escribe en el tenant configurado, aunque exista el mismo teléfono en otro.
        String phone = newPhone();
        contacts.save(new Contact("otro-tenant", "Ana en otra empresa", ChannelType.WHATSAPP, phone));

        signedPost(WebhookTestSupport.textMessage(PHONE_ID, phone, "Ana", newId(), "hola")).andExpect(status().isOk());

        assertThat(contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone)).isPresent();
        assertThat(contacts.findByTenantIdAndIdentityKey("otro-tenant", "WHATSAPP:" + phone).orElseThrow().getName())
                .isEqualTo("Ana en otra empresa");
    }

    // --- helpers ---

    private ResultActions signedPost(String body) throws Exception {
        return mockMvc.perform(rawPost(body, WebhookTestSupport.sign(body)));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rawPost(String body,
            String signature) {
        return post(URL).contentType(MediaType.APPLICATION_JSON).header("X-Hub-Signature-256", signature)
                .content(bytes(body));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String payloadAt(String phone, String id, String epochSeconds, String text) {
        return WebhookTestSupport.withMessage(PHONE_ID, phone, "Ana",
                "{\"from\":\"" + phone + "\",\"id\":\"" + id + "\",\"timestamp\":\"" + epochSeconds
                        + "\",\"type\":\"text\",\"text\":{\"body\":\"" + text + "\"}}");
    }

    private void assertNothingStored(String phone, String messageId) {
        assertThat(contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone)).isEmpty();
        assertThat(messages.findByTenantIdAndExternalId(TENANT, messageId)).isEmpty();
    }

    private void assertInbound(String externalId, String expectedContent) {
        Message message = messages.findByTenantIdAndExternalId(TENANT, externalId).orElseThrow();
        assertThat(message.getDirection()).isEqualTo(Direction.INBOUND);
        assertThat(message.getType()).isEqualTo(MessageType.TEXT);
        assertThat(message.getContent()).isEqualTo(expectedContent);
    }

    private String contactName(String phone) {
        return contacts.findByTenantIdAndIdentityKey(TENANT, "WHATSAPP:" + phone).orElseThrow().getName();
    }

    private DeliveryStatus deliveryStatus(String externalId) {
        return messages.findByTenantIdAndExternalId(TENANT, externalId).orElseThrow().getDeliveryStatus();
    }

    /** Conversación existente con un mensaje saliente ya enviado, para probar los `statuses`. */
    private Conversation seedConversationWithOutbound(String phone, String outId, DeliveryStatus status) {
        Contact contact = contacts.save(new Contact(TENANT, "Cliente " + phone, ChannelType.WHATSAPP, phone));
        Conversation conversation = conversations.save(new Conversation(TENANT, contact.getId(), ChannelType.WHATSAPP));
        messages.save(new Message(TENANT, conversation.getId(), Direction.OUTBOUND, MessageType.TEXT, "respuesta",
                outId, "agent-1", Instant.now(), status));
        return conversation;
    }

    private static String newPhone() {
        return "57" + SEQUENCE.incrementAndGet();
    }

    private static String newId() {
        return "wamid." + SEQUENCE.incrementAndGet();
    }
}
