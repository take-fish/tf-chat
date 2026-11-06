package com.fishmatrix.chat.agent;

/**
 * 随 @ 提问一起发给 agent 的单张图片。
 *
 * <p>只在一次 HTTP 请求里使用：不写日志、不落库、不放进 ChatMessage。
 * Base64 用标准编码（不是 URL-safe），与 agent 侧 Pydantic 校验一致。</p>
 */
public record AgentImage(String mime, String base64, int bytes) {
}
