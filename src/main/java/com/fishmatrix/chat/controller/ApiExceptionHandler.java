package com.fishmatrix.chat.controller;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "参数不合法" : exception.getMessage();
        return Map.of("error", "BAD_REQUEST", "message", message);
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> unavailable(DataAccessException exception) {
        return Map.of("error", "CHAT_UNAVAILABLE", "message", "消息通道暂不可用");
    }
}
