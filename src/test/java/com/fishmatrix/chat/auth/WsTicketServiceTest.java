package com.fishmatrix.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatIdentity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class WsTicketServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private WsTicketService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new WsTicketService(redis, objectMapper, properties());
    }

    @Test
    void issuesOneTimeTicketStoredInRedis() {
        WsTicketService.Ticket ticket = service.issue(ChatIdentity.of(1, "甲", "USER"));

        assertThat(ticket.ticket()).hasSize(32);
        assertThat(ticket.expiresIn()).isEqualTo(60);
        verify(values).set(
                eq("tf:chat:ws-ticket:" + ticket.ticket()),
                anyString(),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void consumesTicketExactlyOnce() throws Exception {
        ChatIdentity identity = ChatIdentity.of(9, "乙", "USER");
        when(values.getAndDelete("tf:chat:ws-ticket:abc"))
                .thenReturn(objectMapper.writeValueAsString(identity));

        assertThat(service.consume("abc")).contains(identity);
        assertThat(service.consume("")).isEmpty();
        assertThat(service.consume(null)).isEmpty();
        verify(values, never()).getAndDelete("tf:chat:ws-ticket:");
    }

    @Test
    void rejectsUnknownOrMalformedTickets() {
        when(values.getAndDelete("tf:chat:ws-ticket:bad")).thenReturn("not-json");

        assertThat(service.consume("missing")).isEmpty();
        assertThat(service.consume("bad")).isEmpty();
    }

    private static ChatProperties properties() {
        return new ChatProperties(
                false, 60_000, 1000, 500, 200, 2000, 32,
                "http://user", 60, 60, 120, 20, List.of("*"),
                "target/chat-images", 5_242_880, 1_048_576);
    }
}
