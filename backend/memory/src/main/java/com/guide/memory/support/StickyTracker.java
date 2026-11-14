package com.guide.memory.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sticky 2 轮：背景记忆命中后，接下来两轮继续注入，避免患者改用代词（「这个病」）时背景突然消失。
 *
 * <p><b>为什么按会话记账</b>：状态天然是「这条会话里的第几轮」，而不是用户级或个人档案；
 * 会话结束即失去意义（进程重启丢失也可接受——sticky 只影响代词指代那两轮）。
 *
 * <p><b>轮次口径</b>：命中轮的 {@link #remember} 置 2；下一轮 {@link #collectAndAdvance}
 * 收集并减到 1，再下一轮收集并减到 0 后清理。所以「接下来 2 轮」是命中轮之后的 R+1、R+2。
 * 同一会话里再次命中会把剩余轮数重置回 2（不累加）。
 *
 * <p>纯内存、线程安全：每个会话一张 {@link ConcurrentHashMap}，收集与递减在会话级加锁，
 * 避免两个并发请求把同一计数减两次。
 */
@Component
public class StickyTracker {

    /** 命中后继续注入的轮数 */
    static final int STICKY_ROUNDS = 2;

    private final Map<String, Map<String, State>> sessions = new ConcurrentHashMap<>();

    /**
     * 收集本轮仍生效的 sticky 命中并推进计数。
     *
     * @return 命中的记忆与其最近一次相似度（供 prompt 排序；仅本轮未再次向量命中时使用）
     */
    public List<Hit> collectAndAdvance(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Map<String, State> states = sessions.get(sessionId);
        if (states == null) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        synchronized (states) {
            states.forEach((memoryId, state) -> hits.add(new Hit(memoryId, state.score())));
            states.replaceAll((memoryId, state) -> new State(state.remaining() - 1, state.score()));
            states.entrySet().removeIf(entry -> entry.getValue().remaining() <= 0);
            if (states.isEmpty()) {
                sessions.remove(sessionId, states);
            }
        }
        return hits;
    }

    /** 本轮向量命中：把剩余轮数重置为 {@link #STICKY_ROUNDS}。 */
    public void remember(String sessionId, String memoryId, double score) {
        if (sessionId == null || sessionId.isBlank() || memoryId == null || memoryId.isBlank()) {
            return;
        }
        Map<String, State> states = sessions.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        synchronized (states) {
            states.put(memoryId, new State(STICKY_ROUNDS, score));
        }
    }

    /** sticky 命中：记忆 id + 最近一次相似度 */
    public record Hit(String memoryId, double score) {
    }

    private record State(int remaining, double score) {
    }
}
