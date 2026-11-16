package com.guide.memory.job;

import com.guide.memory.service.ProfileCandidateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期清理心跳：调一次服务、失败不外抛（后台维护不能影响应用健康）。
 */
class ProfileCandidateExpiryJobTest {

    @Test
    @DisplayName("每次心跳调用一次过期清理")
    void runsExpiry() {
        ProfileCandidateService service = mock(ProfileCandidateService.class);
        new ProfileCandidateExpiryJob(service).expire();

        verify(service).expireOverdue(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("清理失败只记日志，不抛给调度线程")
    void failureOnlyWarns() {
        ProfileCandidateService service = mock(ProfileCandidateService.class);
        when(service.expireOverdue(any(LocalDateTime.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> new ProfileCandidateExpiryJob(service).expire())
                .doesNotThrowAnyException();
    }
}
