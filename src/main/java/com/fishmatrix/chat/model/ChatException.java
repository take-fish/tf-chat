package com.fishmatrix.chat.model;

import org.springframework.http.HttpStatus;

/**
 * 带业务错误码的聊天异常，由 ApiExceptionHandler 映射为稳定的 {error, message} 响应。
 */
public class ChatException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ChatException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ChatException unauthorized(String message) {
        return new ChatException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ChatException forbidden(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ChatException notFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ChatException badRequest(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ChatException rateLimited(String message) {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }

    public static ChatException unavailable(String message) {
        return new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_UNAVAILABLE", message);
    }
}
