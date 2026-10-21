package com.fishmatrix.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.discovery.enabled=false",
            "app.chat.relay-enabled=false"
        })
class ChatApplicationTests {

    @Test
    void contextLoads() {
    }
}
