package com.guide.memory.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 记忆状态（user_memory.status）。
 *
 * <p>{@code active} = 可注入；{@code expired} = 已过期（如已结束的长期用药），
 * 仍留在库里可查，但召回与列表都不应再把它当作生效项。
 */
@Getter
@RequiredArgsConstructor
public enum MemoryStatus {

    ACTIVE("active"),
    EXPIRED("expired");

    @EnumValue
    private final String code;
}
