package com.estatedevin.codemcp.ollama;

import io.netty.channel.ChannelOption;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

@Configuration
class OllamaHttpClientConfiguration {

    @Bean
    RestClientCustomizer ollamaRestClientTimeouts(OllamaCodeAssistantProperties properties) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.connectionTimeout());
            requestFactory.setReadTimeout(properties.requestTimeout());
            builder.requestFactory(requestFactory);
        };
    }

    @Bean
    WebClientCustomizer ollamaWebClientTimeouts(OllamaCodeAssistantProperties properties) {
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient(properties)));
    }

    private static HttpClient httpClient(OllamaCodeAssistantProperties properties) {
        int connectTimeoutMillis = OllamaUtils.toSafeMillis(properties.connectionTimeout());
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(properties.requestTimeout());
    }
}
