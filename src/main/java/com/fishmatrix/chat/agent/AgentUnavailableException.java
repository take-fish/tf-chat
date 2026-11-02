package com.fishmatrix.chat.agent;

/** tf-agent 调用失败：消息直接展示给触发者，不暴露内部地址与堆栈。 */
public class AgentUnavailableException extends RuntimeException {

    public AgentUnavailableException(String message) {
        super(message);
    }

    public AgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
