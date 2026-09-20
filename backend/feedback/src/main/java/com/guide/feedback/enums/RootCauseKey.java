package com.guide.feedback.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * 根因选项字典（feedback，链路 C 配套的「证据快照与根因归因」）。
 *
 * <p><b>存储与显示分离</b>：`root_cause.causes` 存**小写 key**，页面显示 {@link #getLabel()} 中文名——
 * 这样调整文案不会打破统计口径。改文案只动 label，key 不动。
 *
 * <p><b>编码值由常量名派生</b>（{@link #getKey()} = 常量名小写），不给第二个字符串字段，
 * 从根上杜绝「常量名与编码值不一致」这类漂移（枚举约定见《数据库设计.md》§0）。
 * 本枚举**不参与 MyBatis 装载**（`causes` 是 JSON 字符串、不是枚举字段），故无 `@EnumValue`，
 * 也不要登记进 `EnumConventionTest` 的 ENUM_TYPES。
 *
 * <p><b>为什么放在后端</b>：这一项集合有两个消费方——看板要按 key 聚合、并把中文名显示出来；
 * 审核页要拿它渲染选项。在这里唯一定义，前端只显示，避免同义词表在两处漂移。
 * 后端**不校验**存量值：遇到字典外的 key 原样显示（不丢数据），只记 WARN。
 */
@Getter
@RequiredArgsConstructor
public enum RootCauseKey {

    CHUNK_BROKEN("切分破碎"),
    PARSE_MISSED("解析遗漏"),
    RETRIEVAL_FAIL("检索失败"),
    MAPPING_MISSING("局部映射缺失"),
    MODEL_IGNORED_RETRIEVAL("模型未依据检索"),
    STRUCTURE_FAIL("结构化失败"),
    /**
     * 非系统责任。唯一带后端语义的一项：看板准确率口径要排除标了它的记录
     * （见《总体架构与链路设计.md》§7.2 与 stats 的 DashboardService）。
     */
    PATIENT_WRONG("患者挂错");

    /** 展示名（页面显示用） */
    private final String label;

    /** 入库编码值 = 常量名小写 */
    public String getKey() {
        return name().toLowerCase();
    }

    /** 按编码值匹配（禁止用 valueOf——入参是小写编码值，valueOf 匹配常量名会炸，见进度.md 已知坑） */
    public static Optional<RootCauseKey> fromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase();
        for (RootCauseKey value : values()) {
            if (value.getKey().equals(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** 存量值 → 展示名；字典外的值原样返回，保证不丢数据 */
    public static String labelOf(String key) {
        return fromKey(key).map(RootCauseKey::getLabel).orElse(key);
    }
}
