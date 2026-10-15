package com.fishmatrix.chat.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class HealthController {

    @Value("${spring.application.name}")
    private String serviceName;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("service", serviceName, "status", "UP");
    }
}
