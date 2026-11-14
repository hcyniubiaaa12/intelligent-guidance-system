package com.guide.common.model;

import java.time.LocalDate;

/**
 * 长期记忆召回命中（{@link com.guide.common.port.MemoryRecallPort} 的产出）。
 *
 * <p>rag 不感知 memory 模块的实体：这里只带**渲染 prompt 需要的最小字段**。
 * {@code type} 用编码字符串（{@code preference} / {@code background_health} / {@code background_other}），
 * 避免 common 反向依赖 memory 的枚举。
 *
 * @param id          记忆 id（相关提醒/证据快照要能指回具体一条）
 * @param type        记忆类型编码
 * @param key         记忆键（如「慢病」「回答方式」）
 * @param value       记忆值（真正注入 prompt 的文本）
 * @param score       召回相似度（无召回时为 0）
 * @param sourceQuote 患者原话出处（可追溯）
 * @param validTo     有效期截止（空 = 无期限）
 */
public record MemoryHit(
        String id,
        String type,
        String key,
        String value,
        double score,
        String sourceQuote,
        LocalDate validTo) {
}
