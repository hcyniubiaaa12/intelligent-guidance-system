package com.guide.auth.support;

import java.util.List;

/**
 * 存量档案迁移的只读快照（auth 提供，memory 消费）。
 *
 * <p><b>为什么由 auth 出这个 DTO</b>：{@code user_health_profile} 的属主是 auth，
 * memory 不直接碰这张表。迁移只需要旧慢病字段（迁进 {@code user_memory}）与用药/过敏的
 * **原始 JSON**（用于字符串数组 → 对象数组的格式转换），其余列不暴露。
 *
 * <p>{@code medicationTagsJson} / {@code allergyTagsJson} 保留原文而非解析结果：
 * 迁移要判断"是字符串数组还是对象数组"，这只能在 JSON 层面判断；解析后的条目会丢掉这个信息。
 *
 * @param userId            档案归属用户
 * @param historyTags       旧慢病标签（已解析；空列表 = 无标签）
 * @param historyOther      旧慢病自由文本（原文；null = 未填）
 * @param medicationTagsJson 用药列原始 JSON（可能为老字符串数组 / 新对象数组 / null）
 * @param allergyTagsJson    过敏列原始 JSON（过敏**保持字符串数组**，迁移只读不改）
 */
public record LegacyProfile(String userId,
                            List<String> historyTags,
                            String historyOther,
                            String medicationTagsJson,
                            String allergyTagsJson) {
}
