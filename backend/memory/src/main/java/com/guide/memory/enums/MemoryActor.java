package com.guide.memory.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 记忆变更的操作者（user_memory_log.actor）：模型抽取 / 患者本人 / 存量迁移。
 */
@Getter
@RequiredArgsConstructor
public enum MemoryActor {

    EXTRACT("extract"),
    USER("user"),
    MIGRATION("migration");

    @EnumValue
    private final String code;
}
