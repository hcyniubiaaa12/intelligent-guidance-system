package com.guide.memory.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 记忆变更操作（user_memory_log.op）：只追加日志表记录每一次新增/修改/删除/合并/提升。
 */
@Getter
@RequiredArgsConstructor
public enum MemoryOp {

    ADD("add"),
    UPDATE("update"),
    DELETE("delete"),
    /** 偏好满额后由模型合并去重 */
    MERGE("merge"),
    /** 档案候选被接受后提升为记忆（或档案） */
    PROMOTE("promote");

    @EnumValue
    private final String code;
}
