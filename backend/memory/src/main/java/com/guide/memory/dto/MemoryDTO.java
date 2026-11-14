package com.guide.memory.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆控制面读回体（{@code GET /api/memory}）。
 *
 * <p>一次请求拿到「开关 + 偏好 + 背景 + 待确认候选 + 上限」：页面不需要自己拼口径，
 * 也不会出现「按旧上限拦人、被后端按新上限拒绝」的错位。
 *
 * <p>{@code candidates} 本工单固定为空列表（候选由工单 09 接），先占位是为了固定契约形状。
 */
public final class MemoryDTO {

    private MemoryDTO() {
    }

    @Getter
    @Setter
    public static class MemoryVO {
        /** 自动记忆开关（user.memory_enabled）：关闭只停抽取与注入，旧记忆仍可见 */
        private boolean memoryEnabled;
        /** 偏好：全量注入，按 observedAt 降序 */
        private List<MemoryItemVO> preferences = List.of();
        /** 稳定背景：按需召回，按 observedAt 降序 */
        private List<MemoryItemVO> backgrounds = List.of();
        /** 待确认候选：本工单固定为空（工单 09 接） */
        private List<Object> candidates = List.of();
        private Limits limits;
        /** 背景条数达到上限：页面提示患者整理 */
        private boolean needsCleanup;
    }

    @Getter
    @Setter
    public static class MemoryItemVO {
        private String id;
        /** 记忆键，如「回答方式」 */
        private String key;
        /** 记忆值，如「讲慢一点」 */
        private String value;
        /** 患者原话出处（可追溯「从哪句话记的」） */
        private String sourceQuote;
        /** 观察时间 */
        private LocalDateTime observedAt;
        /** 状态编码（active / expired） */
        private String status;
    }

    /** 编辑期上限（来自 sys_config 受管参数，前端不写死） */
    @Getter
    @Setter
    public static class Limits {
        /** 偏好最多条数 */
        private int preferenceMax;
        /** 稳定背景最多条数 */
        private int backgroundMax;
    }

    /** 自动记忆开关请求（{@code PUT /api/memory/setting}） */
    @Getter
    @Setter
    public static class SettingReq {
        /** 必填：缺省视为参数错误，不猜测患者意图 */
        private Boolean memoryEnabled;
    }

    /** 编辑记忆值请求（{@code PUT /api/memory/{id}}）：只提交值，键/出处/时间由后端保留 */
    @Getter
    @Setter
    public static class ValueReq {
        private String value;
    }
}
