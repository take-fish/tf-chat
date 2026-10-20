package com.fishmatrix.chat.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ChatAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_IDENTITY = "chat.identity";

    private final ChatAuthService authService;
    private final ObjectMapper objectMapper;

    public ChatAuthInterceptor(ChatAuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        try {
            ChatIdentity identity = authService.require(request.getHeader(HttpHeaders.AUTHORIZATION));
            request.setAttribute(ATTR_IDENTITY, identity);
            return true;
        } catch (ChatException exception) {
            response.setStatus(exception.getStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getWriter(),
                    Map.of("error", exception.getCode(), "message", exception.getMessage()));
            return false;
        }
    }
}
