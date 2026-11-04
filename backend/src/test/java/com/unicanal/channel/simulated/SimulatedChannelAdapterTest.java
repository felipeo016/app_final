package com.unicanal.channel.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InvalidWebhookPayloadException;
import com.unicanal.channel.OutboundMessage;
import com.unicanal.channel.SendResult;
import com.unicanal.channel.WebhookPayload;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimulatedChannelAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final SimulatedChannelAdapter adapter = new SimulatedChannelAdapter(JsonMapper.builder().build(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void identifiesAsTheSimulatedChannelAndIsAlwaysAvailable() {
        assertThat(adapter.channelType()).isEqualTo(ChannelType.SIMULATED);
        assertThat(adapter.isAvailable()).isTrue();
    }

    @Test
    void sendingReturnsAFakeIdWithoutContactingAnyone() {
        SendResult result = adapter.sendMessage(new OutboundMessage("t1", "v1", "573001112222", "hola", NOW));

        assertThat(result.externalId()).startsWith("sim-out-");
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void toInboundKeepsAProvidedMessageIdToSimulateRetries() {
        InboundMessage message = adapter.toInbound(new SimulateInboundRequest(ChannelType.INSTAGRAM, " ana_ig ",
                "Ana", "hola", "retry-1"));

        assertThat(message.channel()).isEqualTo(ChannelType.INSTAGRAM);
        assertThat(message.contactExternalId()).isEqualTo("ana_ig");
        assertThat(message.contactName()).isEqualTo("Ana");
        assertThat(message.externalMessageId()).isEqualTo("retry-1");
        assertThat(message.type()).isEqualTo(MessageType.TEXT);
        assertThat(message.timestamp()).isEqualTo(NOW);
    }

    @Test
    void toInboundGeneratesAUniqueIdWhenNoneIsProvided() {
        SimulateInboundRequest request = new SimulateInboundRequest(ChannelType.WHATSAPP, "5731", null, "hola", null);

        assertThat(adapter.toInbound(request).externalMessageId())
                .startsWith("sim-in-")
                .isNotEqualTo(adapter.toInbound(request).externalMessageId());
    }

    @Test
    void parsesItsOwnJsonFormat() {
        byte[] json = "{\"channel\":\"FACEBOOK\",\"contactExternalId\":\"fb-1\",\"contactName\":\"Luis\",\"text\":\"hola\"}"
                .getBytes(StandardCharsets.UTF_8);

        WebhookPayload payload = adapter.parseWebhook(json);

        assertThat(payload.statuses()).isEmpty();
        assertThat(payload.messages()).singleElement().satisfies(message -> {
            assertThat(message.channel()).isEqualTo(ChannelType.FACEBOOK);
            assertThat(message.contactExternalId()).isEqualTo("fb-1");
            assertThat(message.content()).isEqualTo("hola");
        });
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> adapter.parseWebhook("no es json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidWebhookPayloadException.class);
    }
}
