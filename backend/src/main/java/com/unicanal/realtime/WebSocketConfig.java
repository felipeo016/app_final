package com.unicanal.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP sobre WebSocket nativo en {@code /ws}, con broker simple en memoria (un solo nodo en el MVP).
 * Los clientes se autentican en el frame CONNECT ({@link StompAuthInterceptor}) y solo escuchan
 * {@code /topic/tenants/{su tenantId}/events}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthInterceptor authInterceptor, WebSocketSessionRegistry sessionRegistry,
            @Value("${app.websocket.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.sessionRegistry = sessionRegistry;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(RealtimeTopics.ENDPOINT).setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(RealtimeTopics.BROKER_PREFIX);
        registry.setApplicationDestinationPrefixes("/app");
        // Cada evento lleva la foto completa de la conversación: si llegaran desordenados, una foto vieja
        // podría pisar a una nueva en la interfaz. Se entregan en el mismo orden en que se publicaron.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionRegistry);
    }
}
