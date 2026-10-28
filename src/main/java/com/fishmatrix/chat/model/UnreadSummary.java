package com.fishmatrix.chat.model;

import java.util.Map;

/**
 * 未读汇总。群聊是临时会话，groups 里永远是 0；dms 的 key 是对方 userId。
 */
public record UnreadSummary(Map<String, Integer> groups, Map<String, Integer> dms, int total) {
}
