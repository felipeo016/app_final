package com.unicanal.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unicanal.channel.WebhookService.WebhookOutcome;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.MessageType;
import com.unicanal.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final byte[] RAW = new byte[0];

    @Mock
    private TenantResolver tenantResolver;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private InboundMessageProcessor inboundProcessor;
    @Mock
    private DeliveryStatusUpdater statusUpdater;
    @Mock
    private ChannelAdapter adapter;

    private WebhookService service;

    @BeforeEach
    void setUp() {
        service = new WebhookService(tenantResolver, tenantRepository, inboundProcessor, statusUpdater);
    }

    private static InboundMessage message(String account, String id) {
        return new InboundMessage(ChannelType.WHATSAPP, account, "5731", null, id, MessageType.TEXT, "hola",
                Instant.now());
    }

    private static StatusUpdate status(String account, String id) {
        return new StatusUpdate(ChannelType.WHATSAPP, account, id, DeliveryStatus.READ, Instant.now());
    }

    private static InboundResult stored() {
        return new InboundResult(false, "c", "v", "m", false);
    }

    @Test
    void ignoresEventsOfAnAccountThatDoesNotMatchTheConfiguredOne() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(
                List.of(message("OTRO", "m1")), List.of(status("OTRO", "o1"))));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "OTRO")).thenReturn(Optional.empty());

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 2, 0));
        verify(inboundProcessor, never()).process(any(), any());
        verify(statusUpdater, never()).apply(any(), any());
    }

    @Test
    void processesMessagesAndStatusesOfSeveralAccountsAndCountsThem() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(
                List.of(message("111", "m1"), message("111", "m2"), message("222", "m3")),
                List.of(status("111", "o1"), status("111", "o2"))));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "222")).thenReturn(Optional.empty());
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(any(), any())).thenReturn(stored(), InboundResult.duplicate("v", "m"));
        when(statusUpdater.apply(any(), any())).thenReturn(true, false);

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(1, 1, 1, 1, 0));
        // La empresa de una cuenta se resuelve y verifica una sola vez por webhook
        verify(tenantResolver).resolve(ChannelType.WHATSAPP, "111");
        verify(tenantRepository).existsById("t1");
    }

    @Test
    void aFailingMessageDoesNotPreventProcessingTheRest() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(
                List.of(message("111", "m1"), message("111", "m2")), List.of(status("111", "o1"))));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(any(), any()))
                .thenThrow(new IllegalStateException("Mongo caído"))
                .thenReturn(stored());
        when(statusUpdater.apply(any(), any())).thenReturn(true);

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(1, 0, 1, 0, 1));
    }

    @Test
    void aConfiguredTenantThatDoesNotExistCountsAsFailureSoTheChannelRetries() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(List.of(message("111", "m1")), List.of()));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("fantasma"));
        when(tenantRepository.existsById("fantasma")).thenReturn(false);

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.stored()).isZero();
        verify(inboundProcessor, never()).process(any(), any());
    }

    @Test
    void anEmptyPayloadProcessesNothing() {
        when(adapter.parseWebhook(RAW)).thenReturn(WebhookPayload.empty());

        assertThat(service.handle(adapter, RAW)).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 0));
    }
}
