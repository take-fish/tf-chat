package com.fishmatrix.chat.model;

import java.util.List;

/**
 * 一页历史消息。messages 按 seq 升序返回，nextBeforeSeq 是继续向前翻页的游标。
 */
public record HistoryPage(List<ChatMessage> messages, boolean hasMore, Long nextBeforeSeq) {
}
