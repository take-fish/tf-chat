package com.fishmatrix.chat.config;

import com.fishmatrix.chat.auth.WsTicketHandshakeInterceptor;
import com.fishmatrix.chat.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final WsTicketHandshakeInterceptor ticketInterceptor;
    private final ChatProperties properties;

    public WebSocketConfig(
            ChatWebSocketHandler chatWebSocketHandler,
            WsTicketHandshakeInterceptor ticketInterceptor,
            ChatProperties properties) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.ticketInterceptor = ticketInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(ticketInterceptor)
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }
}
