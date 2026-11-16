package com.guide.memory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 记忆变更审计日志（user_memory_log）：**只追加**，每次新增/修改/删除/合并/提升一行。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>：日志没有「更新」语义，也没有软删除需求，
 * 表结构只有 id / memory_id / user_id / op / old_value / new_value / actor / created_at。
 * 若继承基类，MyBatis-Plus 会自动带上 deleted / updated_at 条件而表里没有这些列。
 */
@Getter
@Setter
@TableName("user_memory_log")
public class MemoryLog {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String memoryId;

    private String userId;

    private MemoryOp op;

    /** 变更前的值（新增时为空） */
    private String oldValue;

    /** 变更后的值（删除时为空） */
    private String newValue;

    private MemoryActor actor;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
