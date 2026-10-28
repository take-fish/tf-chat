package com.fishmatrix.chat.service;

public interface ChatRateLimiter {

    /**
     * 获取一个发送配额；超出限制时抛出 {@link com.fishmatrix.chat.error.ChatException}。
     */
    void acquire(String userId);
}
