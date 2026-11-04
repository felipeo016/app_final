package com.unicanal.channel;

import com.unicanal.common.TransientErrors;
import com.unicanal.message.MessageType;
import com.unicanal.tenant.TenantRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Procesa un webhook completo: lo interpreta con el adaptador del canal, resuelve la empresa de cada
 * evento y delega en {@link InboundMessageProcessor} y {@link DeliveryStatusUpdater}. Procesa TODOS
 * los mensajes y estados del cuerpo; un fallo en uno no impide procesar los demás.
 *
 * <p>Los errores se separan en dos clases (ver {@link TransientErrors}):
 * <ul>
 *   <li><b>Pasajeros</b> (Mongo caído, timeout): cuentan como {@code failed} y el controlador responde 500
 *       para que el canal reintente. El reintento es seguro porque el procesamiento es idempotente.</li>
 *   <li><b>Permanentes</b> (datos que no se pueden interpretar): reintentar no los arregla. Un mensaje se
 *       guarda como INBOUND con el texto {@value #UNPROCESSABLE_TEXT} para que nadie lo pierda de vista; si
 *       ni eso es posible (p. ej. no se sabe quién lo envió) se registra con log ERROR y se sigue.</li>
 * </ul>
 */
@Service
public class WebhookService {

    public static final String UNPROCESSABLE_TEXT = "[Mensaje no procesable]";

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final TenantResolver tenantResolver;
    private final TenantRepository tenantRepository;
    private final InboundMessageProcessor inboundProcessor;
    private final DeliveryStatusUpdater statusUpdater;

    public WebhookService(TenantResolver tenantResolver, TenantRepository tenantRepository,
            InboundMessageProcessor inboundProcessor, DeliveryStatusUpdater statusUpdater) {
        this.tenantResolver = tenantResolver;
        this.tenantRepository = tenantRepository;
        this.inboundProcessor = inboundProcessor;
        this.statusUpdater = statusUpdater;
    }

    public WebhookOutcome handle(ChannelAdapter adapter, byte[] rawBody) {
        Counters counters = new Counters();
        WebhookPayload payload;
        try {
            payload = adapter.parseWebhook(rawBody);
        } catch (InvalidWebhookPayloadException e) {
            // Cuerpo firmado pero ilegible: reintentarlo produciría siempre el mismo error.
            log.error("Webhook de {} descartado: el cuerpo no es un JSON válido ({} bytes)",
                    adapter.channelType(), rawBody == null ? 0 : rawBody.length);
            counters.unprocessable++;
            return counters.toOutcome();
        }

        Map<String, Optional<String>> tenantByAccount = new HashMap<>();
        Set<String> warnedAccounts = new HashSet<>();

        for (InboundMessage message : payload.messages()) {
            Optional<String> tenantId;
            try {
                tenantId = tenantFor(message.channel(), message.accountId(), tenantByAccount, warnedAccounts);
            } catch (RuntimeException e) {
                counters.failed++;
                log.error("No se pudo resolver la empresa del mensaje {}", message.externalMessageId(), e);
                continue;
            }
            if (tenantId.isEmpty()) {
                counters.ignored++;
                continue;
            }
            try {
                InboundResult result = inboundProcessor.process(tenantId.get(), message);
                if (result.duplicate()) {
                    counters.duplicates++;
                } else {
                    counters.stored++;
                }
            } catch (RuntimeException e) {
                if (TransientErrors.isTransient(e)) {
                    counters.failed++;
                    log.error("Error pasajero al guardar el mensaje {}: se pedirá reintento",
                            message.externalMessageId(), e);
                } else {
                    storeAsUnprocessable(tenantId.get(), message, e, counters);
                }
            }
        }

        for (StatusUpdate status : payload.statuses()) {
            try {
                Optional<String> tenantId = tenantFor(status.channel(), status.accountId(), tenantByAccount,
                        warnedAccounts);
                if (tenantId.isEmpty()) {
                    counters.ignored++;
                    continue;
                }
                if (statusUpdater.apply(tenantId.get(), status)) {
                    counters.statusesApplied++;
                }
            } catch (RuntimeException e) {
                if (TransientErrors.isTransient(e)) {
                    counters.failed++;
                    log.error("Error pasajero al aplicar el estado del mensaje {}: se pedirá reintento",
                            status.externalMessageId(), e);
                } else {
                    counters.unprocessable++;
                    log.error("Estado de entrega no procesable para el mensaje {}: se descarta",
                            status.externalMessageId(), e);
                }
            }
        }
        return counters.toOutcome();
    }

    /**
     * Error permanente con un mensaje: se guarda una versión mínima con el texto {@value #UNPROCESSABLE_TEXT}
     * (mismo remitente e id externo, así un reintento sigue siendo idempotente). Si tampoco se puede, se
     * registra con log ERROR y se continúa con el resto del webhook.
     */
    private void storeAsUnprocessable(String tenantId, InboundMessage original, RuntimeException cause,
            Counters counters) {
        counters.unprocessable++;
        if (original.contactExternalId() == null || original.contactExternalId().isBlank()) {
            log.error("Mensaje {} no procesable y sin remitente: se descarta", original.externalMessageId(), cause);
            return;
        }
        log.error("Mensaje {} no procesable: se guarda como \"{}\"", original.externalMessageId(),
                UNPROCESSABLE_TEXT, cause);
        InboundMessage placeholder = new InboundMessage(original.channel(), original.accountId(),
                original.contactExternalId(), null, original.externalMessageId(), MessageType.TEXT,
                UNPROCESSABLE_TEXT, original.timestamp() != null ? original.timestamp() : Instant.now());
        try {
            inboundProcessor.process(tenantId, placeholder);
        } catch (RuntimeException e) {
            if (TransientErrors.isTransient(e)) {
                counters.unprocessable--;
                counters.failed++;
                log.error("Error pasajero al guardar el mensaje no procesable {}: se pedirá reintento",
                        original.externalMessageId(), e);
            } else {
                log.error("Tampoco se pudo guardar el mensaje {} como no procesable: se descarta",
                        original.externalMessageId(), e);
            }
        }
    }

    /**
     * Empresa del evento. Vacío si la cuenta no corresponde a ninguna (evento ignorado, con aviso en el
     * log). Si la empresa configurada no existe es un error de configuración corregible: se trata como
     * pasajero para que el canal reintente y el evento no se pierda mientras se arregla.
     */
    private Optional<String> tenantFor(ChannelType channel, String accountId,
            Map<String, Optional<String>> cache, Set<String> warnedAccounts) {
        String cacheKey = channel + ":" + accountId;
        Optional<String> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Optional<String> resolved = tenantResolver.resolve(channel, accountId);
        if (resolved.isEmpty() && warnedAccounts.add(cacheKey)) {
            log.warn("Evento ignorado: la cuenta {} del canal {} no coincide con la configurada", accountId, channel);
        }
        if (resolved.isPresent() && !tenantRepository.existsById(resolved.get())) {
            log.error("El tenant configurado para la cuenta {} no existe; se rechaza para que el canal reintente",
                    accountId);
            throw new TransientErrors.RetryableException("Tenant configurado inexistente");
        }
        cache.put(cacheKey, resolved);
        return resolved;
    }

    /**
     * Resumen de un webhook procesado. {@code failed > 0} (solo errores pasajeros) implica que el canal debe
     * reintentar; {@code unprocessable} cuenta los eventos con errores permanentes, que no se reintentan.
     */
    public record WebhookOutcome(int stored, int duplicates, int statusesApplied, int ignored, int unprocessable,
            int failed) {
    }

    private static final class Counters {
        int stored;
        int duplicates;
        int statusesApplied;
        int ignored;
        int unprocessable;
        int failed;

        WebhookOutcome toOutcome() {
            return new WebhookOutcome(stored, duplicates, statusesApplied, ignored, unprocessable, failed);
        }
    }
}
