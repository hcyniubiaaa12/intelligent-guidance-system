package com.guide.chat.port;

import java.time.LocalDateTime;

/**
 * 敏感词命中计数的**读端口**（chat 声明、feedback 实现）。
 *
 * <p>为什么用端口而不是直接查表：计数要读 {@code filter_log}，那是 feedback 的表，
 * 而依赖方向是 **feedback → chat 单向**（chat 只发 {@code SensitiveHitEvent}）。声明在消费方、
 * 实现在数据持有方，与 rag 的 {@code ChunkTextProvider}（rag 声明、kb 实现）同一套路——
 * 于是 chat 既能拿到窗口计数，又不依赖任何业务模块。
 */
public interface SensitiveHitCounter {

    /**
     * 统计某用户自 {@code since} 起（含）的命中**词次**。
     *
     * <p>口径是「词次」而不是「消息数」：一条消息里命中两个观察词算两次。
     * 端口只负责数与词无关的原始计数，阈值判定与处置在 chat 侧（业务语义归业务）。
     */
    Counts countSince(String userId, LocalDateTime since);

    /** 禁止词（banned → blocked）与观察词（watch → watched）的命中词次 */
    record Counts(long banned, long watch) {

        public static Counts zero() {
            return new Counts(0L, 0L);
        }
    }
}
