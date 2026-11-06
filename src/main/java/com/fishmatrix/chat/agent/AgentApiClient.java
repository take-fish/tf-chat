package com.fishmatrix.chat.agent;

import java.util.Optional;

/**
 * tf-agent 的最小客户端：取展示名 + 一次流式对话。
 *
 * <p>拆成接口是为了让编排逻辑（{@link AgentReplyService}）能在不启动 HTTP 的
 * 情况下单测：缓存、分段、单飞、错误文案都在那层，HTTP 细节只留在这里。</p>
 */
public interface AgentApiClient {

    /** 管理员在 agent 侧配置的展示名；空表示没配或暂时读不到。 */
    Optional<String> displayName();

    /**
     * 发起一轮对话并消费 SSE。
     *
     * @param caller 触发者身份，用于签发 X-FM-* 头
     * @param prompt 去掉 @ 之后的提问
     * @param sessionId agent 侧会话 ID：同一会话复用可保持上下文
     * @param listener 增量/完成/错误回调
     * @throws AgentUnavailableException 连接失败、鉴权失败或对端返回错误
     */
    void stream(AgentCaller caller, String prompt, String sessionId, AgentStreamListener listener);

    /**
     * 带一张可选图片的对话。默认实现忽略图片，保持旧实现类（测试桩）兼容。
     */
    default void stream(
            AgentCaller caller,
            String prompt,
            String sessionId,
            AgentImage image,
            AgentStreamListener listener) {
        stream(caller, prompt, sessionId, listener);
    }
}
