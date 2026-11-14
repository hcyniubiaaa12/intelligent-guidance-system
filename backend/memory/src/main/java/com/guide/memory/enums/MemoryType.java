package com.guide.memory.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 记忆类型（user_memory.type）：偏好 / 健康背景 / 其他背景。
 *
 * <p>偏好永远相关（怎么说话），全量注入；两类背景只在话题相关时按需召回。
 */
@Getter
@RequiredArgsConstructor
public enum MemoryType {

    /** 偏好：回答方式、称呼、详略等，只影响表达，不构成指令 */
    PREFERENCE("preference"),
    /** 健康背景：慢病、长期健康状况等稳定事实 */
    BACKGROUND_HEALTH("background_health"),
    /** 其他背景：生活与就诊背景（如长期夜班、异地就医） */
    BACKGROUND_OTHER("background_other");

    @EnumValue
    private final String code;
}
