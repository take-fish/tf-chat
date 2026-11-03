package com.fishmatrix.chat.agent;

/** 一次助手回合的流式回调；实现里不要阻塞，处理逻辑在调用方的线程完成。 */
public interface AgentStreamListener {

    /** 模型增量文本。 */
    void onDelta(String text);

    /** 工具开始/结束；聊天里只在开始时报一次「正在查…」，避免刷屏。 */
    default void onTool(String name, String status) {
    }

    /** 正常结束，带上完整回答（可能为空，例如只触发了写操作确认）。 */
    void onComplete(String fullText);

    /** 失败或超时；此时不会再有 onComplete。 */
    void onError(String message);
}
