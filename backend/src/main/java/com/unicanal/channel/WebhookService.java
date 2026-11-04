package com.unicanal.channel;

import com.unicanal.tenant.TenantRepository;
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
 */
@Service
public class WebhookService {

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
        WebhookPayload payload = adapter.parseWebhook(rawBody);
        Counters counters = new Counters();
        Map<String, Optional<String>> tenantByAccount = new HashMap<>();
        Set<String> warnedAccounts = new HashSet<>();

        for (InboundMessage message : payload.messages()) {
            try {
                Optional<String> tenantId = tenantFor(message.channel(), message.accountId(), tenantByAccount,
                        warnedAccounts);
                if (tenantId.isEmpty()) {
                    counters.ignored++;
                    continue;
                }
                InboundResult result = inboundProcessor.process(tenantId.get(), message);
                if (result.duplicate()) {
                    counters.duplicates++;
                } else {
                    counters.stored++;
                }
            } catch (RuntimeException e) {
                counters.failed++;
                log.error("No se pudo guardar un mensaje entrante (id externo {})", message.externalMessageId(), e);
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
                counters.failed++;
                log.error("No se pudo aplicar un estado de entrega (id externo {})", status.externalMessageId(), e);
            }
        }
        return new WebhookOutcome(counters.stored, counters.duplicates, counters.statusesApplied, counters.ignored,
                counters.failed);
    }

    /**
     * Empresa del evento. Vacío si la cuenta no corresponde a ninguna (evento ignorado, con aviso en el
     * log) o si la empresa configurada no existe (se cuenta como fallo para que el canal reintente).
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
            throw new IllegalStateException("Tenant configurado inexistente");
        }
        cache.put(cacheKey, resolved);
        return resolved;
    }

    /** Resumen de un webhook procesado. {@code failed > 0} implica que el canal debe reintentar. */
    public record WebhookOutcome(int stored, int duplicates, int statusesApplied, int ignored, int failed) {
    }

    private static final class Counters {
        int stored;
        int duplicates;
        int statusesApplied;
        int ignored;
        int failed;
    }
}
