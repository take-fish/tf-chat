package com.fishmatrix.chat.error;

import com.fishmatrix.common.error.ApiException;
import com.fishmatrix.common.error.ErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * 聊天业务异常，继承 tf-common 的统一异常类型，由全局异常处理器输出错误响应。
 */
public class ChatException extends ApiException {

    public ChatException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static ChatException unauthorized(String message) {
        return new ChatException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, message);
    }

    public static ChatException forbidden(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, message);
    }

    public static ChatException notFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    public static ChatException badRequest(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, message);
    }

    public static ChatException rateLimited(String message) {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, message);
    }

    public static ChatException unavailable(String message) {
        return new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_UNAVAILABLE", message);
    }
}
