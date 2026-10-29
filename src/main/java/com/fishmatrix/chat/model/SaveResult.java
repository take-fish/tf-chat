package com.fishmatrix.chat.model;

/**
 * 消息落库结果。created=false 表示命中幂等（同一发送者的同一 clientMsgId 已经存在），
 * 此时不应再次广播。
 */
public record SaveResult(ChatMessage message, boolean created) {
}
