package com.unicanal.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class WebSocketSessionRegistryTest {

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    @Test
    void closingATenantRestartsOnlyItsSessionsSoClientsReconnectAndReload() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(Clock.systemUTC());
        WebSocketHandler handler = registry.decorate(mock(WebSocketHandler.class));
        WebSocketSession a1 = session("a1");
        WebSocketSession a2 = session("a2");
        WebSocketSession b1 = session("b1");
        for (WebSocketSession s : new WebSocketSession[] {a1, a2, b1}) {
            handler.afterConnectionEstablished(s);
        }
        Instant later = Instant.now().plusSeconds(3600);
        registry.bind("a1", "u1", "tenant-a", later);
        registry.bind("a2", "u2", "tenant-a", later);
        registry.bind("b1", "u3", "tenant-b", later);

        int closed = registry.closeSessionsOfTenant("tenant-a", "Datos demo restaurados");

        assertThat(closed).isEqualTo(2);
        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(a1).close(status.capture());
        verify(a2).close(any(CloseStatus.class));
        verify(b1, never()).close(any(CloseStatus.class));
        // 1012 (reinicio), no 1008: el cliente no cierra la sesión del usuario, solo se reconecta
        assertThat(status.getValue().getCode()).isEqualTo(1012);
        assertThat(status.getValue().getReason()).isEqualTo("Datos demo restaurados");
    }
}
