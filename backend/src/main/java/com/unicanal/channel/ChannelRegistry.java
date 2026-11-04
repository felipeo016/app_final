package com.unicanal.channel;

import com.unicanal.common.ApiException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Localiza el adaptador de cada canal. En el perfil {@code dev}, un canal sin adaptador utilizable
 * (Instagram/Facebook aún no implementados, o WhatsApp sin credenciales) responde con el
 * {@link com.unicanal.channel.simulated.SimulatedChannelAdapter}, para poder demostrar el flujo completo.
 * Fuera de dev, un canal no disponible es un error explícito.
 */
@Component
public class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<ChannelType, ChannelAdapter> adapters = new EnumMap<>(ChannelType.class);
    private final boolean devFallback;

    public ChannelRegistry(List<ChannelAdapter> adapterList, Environment environment) {
        adapterList.forEach(adapter -> adapters.put(adapter.channelType(), adapter));
        this.devFallback = environment.acceptsProfiles(Profiles.of("dev"));
    }

    public ChannelAdapter adapterFor(ChannelType channel) {
        ChannelAdapter adapter = adapters.get(channel);
        if (adapter != null && adapter.isAvailable()) {
            return adapter;
        }
        ChannelAdapter simulated = adapters.get(ChannelType.SIMULATED);
        if (devFallback && simulated != null) {
            log.debug("Canal {} no disponible: se usa el adaptador simulado (perfil dev)", channel);
            return simulated;
        }
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "CHANNEL_NOT_AVAILABLE",
                "El canal " + channel + " no está disponible");
    }
}
