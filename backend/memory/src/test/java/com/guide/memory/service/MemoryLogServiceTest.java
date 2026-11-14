package com.guide.memory.service;

import com.guide.memory.entity.MemoryLog;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.mapper.MemoryLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 记忆审计日志：只追加一行，操作前后的值与操作者如实落库；必填项缺失当场失败，
 * 不等到写库时才发现。</p>
 */
class MemoryLogServiceTest {

    private MemoryLogMapper memoryLogMapper;
    private MemoryLogService service;

    @BeforeEach
    void setUp() {
        memoryLogMapper = mock(MemoryLogMapper.class);
        service = new MemoryLogService(memoryLogMapper);
    }

    @Test
    @DisplayName("追加一条修改日志：memoryId / 用户 / 操作 / 前后值 / 操作者都落库")
    void appendRecordsChange() {
        service.append("m1", "u1", MemoryOp.UPDATE, "慢一点", "再慢一点", MemoryActor.USER);

        ArgumentCaptor<MemoryLog> captor = ArgumentCaptor.forClass(MemoryLog.class);
        verify(memoryLogMapper).insert(captor.capture());
        MemoryLog log = captor.getValue();
        assertThat(log.getMemoryId()).isEqualTo("m1");
        assertThat(log.getUserId()).isEqualTo("u1");
        assertThat(log.getOp()).isEqualTo(MemoryOp.UPDATE);
        assertThat(log.getOldValue()).isEqualTo("慢一点");
        assertThat(log.getNewValue()).isEqualTo("再慢一点");
        assertThat(log.getActor()).isEqualTo(MemoryActor.USER);
    }

    @Test
    @DisplayName("必填项缺失：当场拒绝，不写一条没有归属的日志")
    void rejectsMissingRequired() {
        assertThrows(NullPointerException.class,
                () -> service.append(null, "u1", MemoryOp.ADD, null, "慢一点", MemoryActor.EXTRACT));
        assertThrows(NullPointerException.class,
                () -> service.append("m1", "u1", null, null, "慢一点", MemoryActor.EXTRACT));
    }
}
