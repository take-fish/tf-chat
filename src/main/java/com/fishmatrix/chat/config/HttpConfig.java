package com.fishmatrix.chat.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpConfig {

    /**
     * 使用 Spring Boot 提供的 Builder，让 tf-common 注册的链路拦截器自动生效。
     */
    @Bean
    public RestClient chatUserRestClient(RestClient.Builder builder, ChatProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return builder
                .baseUrl(properties.userServiceUrl())
                .requestFactory(factory)
                .build();
    }
}
