package com.guide.memory.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * 记忆判重的三种结果（{@code memory-extract} 输出协议里的 {@code action}）。
 *
 * <p><b>不参与 MyBatis 装载</b>：它只活在「模型输出 → 解析 → 落库编排」这一小段里，
 * 不进任何表（表结构不变：{@code user_memory_log.op} 已有 update 操作码）。
 * 故无 {@code @EnumValue}，也不登记进 {@code EnumConventionTest} 的 ENUM_TYPES
 * （与 {@code RootCauseKey} 同一处理方式）。
 *
 * <p><b>编码值由常量名派生</b>，与提示词里写的 {@code new / update / skip} 逐字一致。
 */
@Getter
@RequiredArgsConstructor
public enum MemoryDedupAction {

    /** 没说过的：插入新行 */
    NEW("新增"),
    /** 同一条，新说法更具体或相反：原地改旧行（id 不变） */
    UPDATE("更新"),
    /** 旧值已经更好或等价：什么都不写 */
    SKIP("跳过");

    /** 展示名（日志用；患者端不展示判重结果） */
    private final String label;

    /** 协议编码值 = 常量名小写 */
    public String getCode() {
        return name().toLowerCase();
    }

    /**
     * 按协议编码值解析；缺失、空白与任何不认识的值都返回 {@code empty}——
     * 调用方据此降级为 {@link #NEW}（模型没说话时「宁可重复」）。
     */
    public static Optional<MemoryDedupAction> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase();
        for (MemoryDedupAction value : values()) {
            if (value.getCode().equals(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
