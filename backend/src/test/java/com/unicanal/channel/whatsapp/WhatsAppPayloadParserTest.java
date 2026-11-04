package com.unicanal.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InvalidWebhookPayloadException;
import com.unicanal.channel.StatusUpdate;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class WhatsAppPayloadParserTest {

    private final WhatsAppPayloadParser parser = new WhatsAppPayloadParser(JsonMapper.builder().build());

    private WebhookPayload parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private InboundMessage single(String messageJson) {
        WebhookPayload payload = parse(WebhookTestSupport.withMessage("111", "573001112222", "Ana", messageJson));
        assertThat(payload.messages()).hasSize(1);
        return payload.messages().get(0);
    }

    @Test
    void parsesATextMessageWithProfileName() {
        WebhookPayload payload = parse(
                WebhookTestSupport.textMessage("111", "573001112222", "Ana Pérez", "wamid.A", "Hola, ¿tienen stock?"));

        assertThat(payload.statuses()).isEmpty();
        assertThat(payload.messages()).singleElement().satisfies(message -> {
            assertThat(message.channel()).isEqualTo(ChannelType.WHATSAPP);
            assertThat(message.accountId()).isEqualTo("111");
            assertThat(message.contactExternalId()).isEqualTo("573001112222");
            assertThat(message.contactName()).isEqualTo("Ana Pérez");
            assertThat(message.externalMessageId()).isEqualTo("wamid.A");
            assertThat(message.type()).isEqualTo(MessageType.TEXT);
            assertThat(message.content()).isEqualTo("Hola, ¿tienen stock?");
            assertThat(message.timestamp()).isEqualTo(Instant.ofEpochSecond(1730000000L));
        });
    }

    @Test
    void contactNameIsNullWhenThePayloadHasNoProfile() {
        WebhookPayload payload = parse(
                WebhookTestSupport.textMessage("111", "573001112222", null, "wamid.A", "hola"));

        assertThat(payload.messages().get(0).contactName()).isNull();
    }

    @Test
    void processesEveryEntryChangeMessageAndStatus() {
        String json = """
                {"object":"whatsapp_business_account","entry":[
                  {"id":"w1","changes":[
                    {"field":"messages","value":{"metadata":{"phone_number_id":"111"},
                      "contacts":[{"profile":{"name":"Ana"},"wa_id":"5731"},{"profile":{"name":"Luis"},"wa_id":"5732"}],
                      "messages":[
                        {"from":"5731","id":"m1","timestamp":"1730000001","type":"text","text":{"body":"uno"}},
                        {"from":"5732","id":"m2","timestamp":"1730000002","type":"text","text":{"body":"dos"}}],
                      "statuses":[{"id":"o1","status":"sent","timestamp":"1730000003"},
                                  {"id":"o2","status":"delivered","timestamp":"1730000004"}]}},
                    {"field":"account_update","value":{"event":"VERIFIED"}},
                    {"field":"messages","value":{"metadata":{"phone_number_id":"111"},
                      "messages":[{"from":"5733","id":"m3","timestamp":"1730000005","type":"text","text":{"body":"tres"}}],
                      "statuses":[{"id":"o3","status":"read","timestamp":"1730000006"},
                                  {"id":"o4","status":"failed","timestamp":"1730000007"}]}}]},
                  {"id":"w2","changes":[
                    {"field":"messages","value":{"metadata":{"phone_number_id":"222"},
                      "messages":[{"from":"5734","id":"m4","timestamp":"1730000008","type":"text","text":{"body":"cuatro"}}]}}]}
                ]}""";

        WebhookPayload payload = parse(json);

        assertThat(payload.messages()).extracting(InboundMessage::externalMessageId)
                .containsExactly("m1", "m2", "m3", "m4");
        assertThat(payload.messages()).extracting(InboundMessage::contactName)
                .containsExactly("Ana", "Luis", null, null);
        assertThat(payload.messages()).extracting(InboundMessage::accountId)
                .containsExactly("111", "111", "111", "222");
        assertThat(payload.statuses()).extracting(StatusUpdate::externalMessageId)
                .containsExactly("o1", "o2", "o3", "o4");
        assertThat(payload.statuses()).extracting(StatusUpdate::status)
                .containsExactly(DeliveryStatus.SENT, DeliveryStatus.DELIVERED, DeliveryStatus.READ,
                        DeliveryStatus.FAILED);
    }

    @Test
    void ignoresStatusesItDoesNotTrack() {
        String json = WebhookTestSupport.statusOnly("111", "wamid.X", "deleted");

        assertThat(parse(json).statuses()).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            {"from":"5731","id":"m","type":"audio","audio":{"id":"1"}} | [Audio recibido]
            {"from":"5731","id":"m","type":"video","video":{"id":"1"}} | [Video recibido]
            {"from":"5731","id":"m","type":"sticker","sticker":{"id":"1"}} | [Sticker recibido]
            {"from":"5731","id":"m","type":"location","location":{"latitude":1,"longitude":2}} | [Ubicación recibida]
            {"from":"5731","id":"m","type":"location","location":{"latitude":1,"longitude":2,"name":"Oficina"}} | [Ubicación recibida]: Oficina
            {"from":"5731","id":"m","type":"contacts","contacts":[{"name":{}}]} | [Contacto compartido]
            {"from":"5731","id":"m","type":"reaction","reaction":{"message_id":"x","emoji":"👍"}} | [Reacción recibida]: 👍
            {"from":"5731","id":"m","type":"document","document":{"filename":"factura.pdf"}} | [Documento recibido]: factura.pdf
            {"from":"5731","id":"m","type":"order","order":{}} | [Pedido recibido]
            {"from":"5731","id":"m","type":"system","system":{}} | [Mensaje del sistema]
            {"from":"5731","id":"m","type":"unsupported"} | [Mensaje no soportado]
            {"from":"5731","id":"m","type":"hologram"} | [Mensaje de tipo hologram recibido]
            {"from":"5731","id":"m","type":"<script>x</script>"} | [Mensaje de tipo scriptxscript recibido]
            {"from":"5731","id":"m"} | [Mensaje recibido]
            {"from":"5731","id":"m","type":"button","button":{"text":"Sí, quiero"}} | Sí, quiero
            {"from":"5731","id":"m","type":"interactive","interactive":{"button_reply":{"id":"b","title":"Confirmar"}}} | Confirmar
            {"from":"5731","id":"m","type":"interactive","interactive":{"list_reply":{"id":"l","title":"Opción 2"}}} | Opción 2
            """)
    void unsupportedTypesAreStoredWithADescriptiveText(String messageJson, String expectedContent) {
        InboundMessage message = single(messageJson);

        assertThat(message.content()).isEqualTo(expectedContent);
        assertThat(message.type()).isEqualTo(MessageType.TEXT);
        assertThat(message.externalMessageId()).isEqualTo("m");
    }

    @Test
    void imagesKeepTheImageTypeAndCaption() {
        InboundMessage plain = single("{\"from\":\"5731\",\"id\":\"m\",\"type\":\"image\",\"image\":{\"id\":\"1\"}}");
        InboundMessage captioned = single(
                "{\"from\":\"5731\",\"id\":\"m\",\"type\":\"image\",\"image\":{\"id\":\"1\",\"caption\":\"mi pedido\"}}");

        assertThat(plain.type()).isEqualTo(MessageType.IMAGE);
        assertThat(plain.content()).isEqualTo("[Imagen recibida]");
        assertThat(captioned.type()).isEqualTo(MessageType.IMAGE);
        assertThat(captioned.content()).isEqualTo("[Imagen recibida]: mi pedido");
    }

    @Test
    void aMessageWithoutSenderIsSkippedWithoutFailingTheRest() {
        String json = WebhookTestSupport.withMessage("111", "5731", null,
                "{\"id\":\"sin-from\",\"type\":\"text\",\"text\":{\"body\":\"x\"}},"
                        + "{\"from\":\"5731\",\"id\":\"ok\",\"type\":\"text\",\"text\":{\"body\":\"y\"}}");

        assertThat(parse(json).messages()).extracting(InboundMessage::externalMessageId).containsExactly("ok");
    }

    @Test
    void aMissingOrInvalidTimestampFallsBackToNow() {
        Instant before = Instant.now();
        InboundMessage message = single("{\"from\":\"5731\",\"id\":\"m\",\"timestamp\":\"no-es-numero\","
                + "\"type\":\"text\",\"text\":{\"body\":\"x\"}}");

        assertThat(message.timestamp()).isBetween(before, Instant.now());
    }

    @Test
    void anObjectThatIsNotWhatsAppYieldsAnEmptyPayload() {
        WebhookPayload payload = parse("{\"object\":\"instagram\",\"entry\":[{\"id\":\"1\"}]}");

        assertThat(payload.messages()).isEmpty();
        assertThat(payload.statuses()).isEmpty();
    }

    @Test
    void aValidObjectWithoutEntriesYieldsAnEmptyPayload() {
        assertThat(parse("{\"object\":\"whatsapp_business_account\"}").messages()).isEmpty();
    }

    @Test
    void rejectsBodiesThatAreNotAJsonObject() {
        assertThatThrownBy(() -> parse("esto no es json")).isInstanceOf(InvalidWebhookPayloadException.class);
        assertThatThrownBy(() -> parse("[1,2,3]")).isInstanceOf(InvalidWebhookPayloadException.class);
        assertThatThrownBy(() -> parse("")).isInstanceOf(InvalidWebhookPayloadException.class);
        assertThatThrownBy(() -> parse("{\"object\":")).isInstanceOf(InvalidWebhookPayloadException.class);
    }
}
