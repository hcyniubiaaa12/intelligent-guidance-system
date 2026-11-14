package com.guide.memory.service;

import com.guide.memory.entity.MemoryLog;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.mapper.MemoryLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 记忆变更审计（user_memory_log）：只追加，不覆盖。
 *
 * <p>本工单只建立写入能力；编辑 / 删除记忆（actor=user）在工单 06 调用。
 * 抽取写入（actor=extract）与存量迁移（actor=migration）同样复用这里。
 */
@Service
@RequiredArgsConstructor
public class MemoryLogService {

    private final MemoryLogMapper memoryLogMapper;

    /**
     * 追加一条变更日志。
     *
     * @param memoryId 被变更的记忆 id
     * @param userId   归属用户
     * @param op       操作类型
     * @param oldValue 变更前值（新增时为空）
     * @param newValue 变更后值（删除时为空）
     * @param actor    操作者
     */
    public void append(String memoryId, String userId, MemoryOp op,
                       String oldValue, String newValue, MemoryActor actor) {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(actor, "actor");

        MemoryLog log = new MemoryLog();
        log.setMemoryId(memoryId);
        log.setUserId(userId);
        log.setOp(op);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setActor(actor);
        memoryLogMapper.insert(log);
    }
}
