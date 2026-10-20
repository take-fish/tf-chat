package com.fishmatrix.chat.controller;

import com.fishmatrix.chat.model.ChatException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Map<String, Object>> chatException(ChatException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of(
                        "error", exception.getCode(),
                        "message", exception.getMessage() == null ? "聊天请求失败" : exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "参数不合法" : exception.getMessage();
        return Map.of("error", "BAD_REQUEST", "message", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> unreadable(HttpMessageNotReadableException exception) {
        return Map.of("error", "BAD_REQUEST", "message", "请求体格式不合法");
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> unavailable(DataAccessException exception) {
        return Map.of("error", "CHAT_UNAVAILABLE", "message", "消息通道暂不可用");
    }
}
