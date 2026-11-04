package com.unicanal.channel.whatsapp;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class WhatsAppConfig {

    /** Cliente de la Graph API con timeouts: un Meta lento no debe colgar los hilos de la aplicación. */
    @Bean
    RestClient whatsAppRestClient(WhatsAppProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(properties.graphApiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
