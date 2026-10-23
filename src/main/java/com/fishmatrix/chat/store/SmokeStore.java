package com.fishmatrix.chat.store;

import java.util.List;

/**
 * 冒烟测试会话的持久化边界。状态量很小，整表读写即可；
 * 读-改-写由 SmokeService 串行化。
 */
public interface SmokeStore {

    List<SmokeSession> load();

    void save(List<SmokeSession> sessions);
}
