package com.fishmatrix.chat.config;

import com.fishmatrix.chat.auth.WsTicketHandshakeInterceptor;
import com.fishmatrix.chat.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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

    /**
     * 群聊图片以 data URL 经 WebSocket 实时转发，需要大于默认 8KB 的文本缓冲。
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(4 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
        return container;
    }
}
