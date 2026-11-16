package com.guide.memory.job;

import com.guide.auth.service.SysConfigService;
import com.guide.common.port.SessionActivityPort;
import com.guide.common.port.SessionActivityPort.IdleSession;
import com.guide.memory.service.MemoryExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空闲兜底任务：扫描参数用 IDLE_FOR / 配额上限 / 单次上限；逐个走抽取；扫描失败不炸调度。
 */
class MemoryIdleExtractionJobTest {

    private SessionActivityPort port;
    private MemoryExtractionService service;
    private SysConfigService sysConfigService;
    private MemoryIdleExtractionJob job;

    @BeforeEach
    void setUp() {
        port = mock(SessionActivityPort.class);
        service = mock(MemoryExtractionService.class);
        sysConfigService = mock(SysConfigService.class);
        job = new MemoryIdleExtractionJob(port, service, sysConfigService, mock(ThreadPoolTaskExecutor.class));
    }

    @Test
    @DisplayName("按「空闲 30 分钟 + 每会话配额 + 单次 50 条」扫描，并逐个抽取")
    void scansAndExtractsEachSession() {
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_EXTRACT_SESSION_MAX,
                SysConfigService.DEFAULT_MEMORY_EXTRACT_SESSION_MAX)).thenReturn(3);
        IdleSession first = new IdleSession("s1", "u1");
        IdleSession second = new IdleSession("s2", "u2");
        when(port.findIdleSessions(MemoryIdleExtractionJob.IDLE_FOR, 3, MemoryIdleExtractionJob.SCAN_LIMIT))
                .thenReturn(List.of(first, second));

        job.scan();

        verify(port).findIdleSessions(eq(MemoryIdleExtractionJob.IDLE_FOR), eq(3),
                eq(MemoryIdleExtractionJob.SCAN_LIMIT));
        verify(service).extractIdleSession(first);
        verify(service).extractIdleSession(second);
    }

    @Test
    @DisplayName("扫描异常：只记 WARN，不把异常抛给调度器（否则后续扫描会被掐掉）")
    void scanFailureDoesNotThrow() {
        when(port.findIdleSessions(any(), anyInt(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> job.scan()).doesNotThrowAnyException();
    }
}
