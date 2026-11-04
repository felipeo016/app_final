package com.unicanal.channel.whatsapp;

import com.unicanal.channel.ChannelType;
import com.unicanal.channel.TenantResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Un solo número de WhatsApp por instalación: los eventos del {@code phone_number_id} configurado
 * pertenecen a la empresa {@code WHATSAPP_DEFAULT_TENANT_ID}; los de cualquier otro número se ignoran.
 * Punto de extensión: sustituir por una búsqueda phone_number_id -> tenant para soportar varios números.
 */
@Component
public class WhatsAppTenantResolver implements TenantResolver {

    private final WhatsAppProperties properties;

    public WhatsAppTenantResolver(WhatsAppProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> resolve(ChannelType channel, String accountId) {
        if (channel != ChannelType.WHATSAPP || accountId == null || properties.phoneNumberId().isBlank()
                || properties.defaultTenantId().isBlank() || !properties.phoneNumberId().equals(accountId)) {
            return Optional.empty();
        }
        return Optional.of(properties.defaultTenantId());
    }
}
