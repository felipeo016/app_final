package com.unicanal.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unicanal.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

class ChannelRegistryTest {

    private static ChannelAdapter adapter(ChannelType type, boolean available) {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        lenient().when(adapter.channelType()).thenReturn(type);
        lenient().when(adapter.isAvailable()).thenReturn(available);
        return adapter;
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void returnsTheRealAdapterWhenItIsAvailable() {
        ChannelAdapter whatsapp = adapter(ChannelType.WHATSAPP, true);
        ChannelRegistry registry = new ChannelRegistry(
                List.of(whatsapp, adapter(ChannelType.SIMULATED, true)), environment("dev"));

        assertThat(registry.adapterFor(ChannelType.WHATSAPP)).isSameAs(whatsapp);
    }

    @Test
    void devFallsBackToTheSimulatedAdapterForUnavailableOrMissingChannels() {
        ChannelAdapter simulated = adapter(ChannelType.SIMULATED, true);
        ChannelRegistry registry = new ChannelRegistry(
                List.of(adapter(ChannelType.WHATSAPP, false), simulated), environment("dev"));

        assertThat(registry.adapterFor(ChannelType.WHATSAPP)).isSameAs(simulated);   // sin credenciales
        assertThat(registry.adapterFor(ChannelType.INSTAGRAM)).isSameAs(simulated);  // sin adaptador
        assertThat(registry.adapterFor(ChannelType.SIMULATED)).isSameAs(simulated);
    }

    @Test
    void outsideDevAnUnavailableChannelIsAnExplicitError() {
        ChannelRegistry registry = new ChannelRegistry(
                List.of(adapter(ChannelType.WHATSAPP, false), adapter(ChannelType.SIMULATED, true)), environment());

        assertThatThrownBy(() -> registry.adapterFor(ChannelType.WHATSAPP))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(e.getCode()).isEqualTo("CHANNEL_NOT_AVAILABLE");
                });
        assertThatThrownBy(() -> registry.adapterFor(ChannelType.INSTAGRAM)).isInstanceOf(ApiException.class);
    }

    @Test
    void simulatedChannelIsAlwaysServedBySimulatedAdapter() {
        ChannelAdapter simulated = adapter(ChannelType.SIMULATED, true);
        ChannelRegistry registry = new ChannelRegistry(List.of(simulated), environment());

        assertThat(registry.adapterFor(ChannelType.SIMULATED)).isSameAs(simulated);
    }
}
