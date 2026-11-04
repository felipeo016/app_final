package com.unicanal.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoTimeoutException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
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

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 2, 0, 0));
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

        assertThat(outcome).isEqualTo(new WebhookOutcome(1, 1, 1, 1, 0, 0));
        // La empresa de una cuenta se resuelve y verifica una sola vez por webhook
        verify(tenantResolver).resolve(ChannelType.WHATSAPP, "111");
        verify(tenantRepository).existsById("t1");
    }

    @Test
    void aTransientFailureInOneMessageDoesNotPreventProcessingTheRest() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(
                List.of(message("111", "m1"), message("111", "m2")), List.of(status("111", "o1"))));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("Mongo caído"))
                .thenReturn(stored());
        when(statusUpdater.apply(any(), any())).thenReturn(true);

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(1, 0, 1, 0, 0, 1));
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

    // --- errores permanentes (datos) vs pasajeros (infraestructura) ---

    @Test
    void aPermanentErrorStoresTheMessageAsUnprocessableAndDoesNotAskForARetry() {
        InboundMessage original = message("111", "m1");
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(List.of(original), List.of()));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(eq("t1"), any()))
                .thenThrow(new IllegalArgumentException("dato inesperado"))
                .thenReturn(stored());

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 1, 0));
        ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inboundProcessor, times(2)).process(eq("t1"), captor.capture());
        InboundMessage placeholder = captor.getAllValues().get(1);
        assertThat(placeholder.content()).isEqualTo(WebhookService.UNPROCESSABLE_TEXT);
        assertThat(placeholder.type()).isEqualTo(MessageType.TEXT);
        // Mismo remitente e id externo: un reintento posterior sigue siendo idempotente
        assertThat(placeholder.contactExternalId()).isEqualTo(original.contactExternalId());
        assertThat(placeholder.externalMessageId()).isEqualTo("m1");
    }

    @Test
    void aPermanentErrorWithoutSenderIsLoggedAndTheRestContinues() {
        InboundMessage withoutSender = new InboundMessage(ChannelType.WHATSAPP, "111", " ", null, "m1",
                MessageType.TEXT, "hola", Instant.now());
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(
                List.of(withoutSender, message("111", "m2")), List.of()));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(eq("t1"), any()))
                .thenThrow(new IllegalArgumentException("El identificador externo del contacto es obligatorio"))
                .thenReturn(stored());

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(1, 0, 0, 0, 1, 0));
        verify(inboundProcessor, times(2)).process(eq("t1"), any());
    }

    @Test
    void ifThePlaceholderAlsoFailsPermanentlyItIsDiscardedWithoutRetry() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(List.of(message("111", "m1")), List.of()));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(eq("t1"), any())).thenThrow(new IllegalStateException("datos corruptos"));

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 1, 0));
    }

    @Test
    void aTransientErrorWhileStoringThePlaceholderAsksForARetry() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(List.of(message("111", "m1")), List.of()));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(eq("t1"), any()))
                .thenThrow(new IllegalArgumentException("dato inesperado"))
                .thenThrow(new DataAccessResourceFailureException("Mongo caído"));

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 0, 1));
    }

    @Test
    void transientErrorsAreMongoDownAndTimeoutsEvenWhenWrapped() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(
                List.of(message("111", "m1"), message("111", "m2"), message("111", "m3")), List.of()));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(inboundProcessor.process(eq("t1"), any()))
                .thenThrow(new QueryTimeoutException("timeout"))
                .thenThrow(new IllegalStateException("envoltorio", new MongoTimeoutException("sin servidor")))
                .thenThrow(new DataAccessResourceFailureException("Mongo caído"));

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 0, 3));
        // Ningún error pasajero se convierte en "[Mensaje no procesable]"
        verify(inboundProcessor, times(3)).process(eq("t1"), any());
    }

    @Test
    void statusErrorsAreRetriedOnlyWhenTransient() {
        when(adapter.parseWebhook(RAW)).thenReturn(new WebhookPayload(List.of(),
                List.of(status("111", "o1"), status("111", "o2"))));
        when(tenantResolver.resolve(ChannelType.WHATSAPP, "111")).thenReturn(Optional.of("t1"));
        when(tenantRepository.existsById("t1")).thenReturn(true);
        when(statusUpdater.apply(eq("t1"), any()))
                .thenThrow(new IllegalArgumentException("estado raro"))
                .thenThrow(new DataAccessResourceFailureException("Mongo caído"));

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 1, 1));
    }

    @Test
    void aSignedButUnreadableBodyIsDiscardedWithoutRetry() {
        when(adapter.parseWebhook(RAW)).thenThrow(new InvalidWebhookPayloadException());

        WebhookOutcome outcome = service.handle(adapter, RAW);

        assertThat(outcome).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 1, 0));
        verify(inboundProcessor, never()).process(any(), any());
    }

    @Test
    void anEmptyPayloadProcessesNothing() {
        when(adapter.parseWebhook(RAW)).thenReturn(WebhookPayload.empty());

        assertThat(service.handle(adapter, RAW)).isEqualTo(new WebhookOutcome(0, 0, 0, 0, 0, 0));
    }
}
