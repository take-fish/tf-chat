package com.fishmatrix.chat.util;

public final class NodeNames {

    private NodeNames() {
    }

    /**
     * 与前端 identity store 使用同一算法，保证终端提示符与消息头部一致。
     */
    public static String forUser(String userId) {
        long hash = 0;
        for (int i = 0; i < userId.length(); i++) {
            hash = (hash * 31 + userId.charAt(i)) & 0xFFFFFFFFL;
        }
        int node = (int) (hash % 99) + 1;
        return "dev-node-" + String.format("%02d", node);
    }
}
