package com.guide.memory.job;

import com.guide.memory.service.ProfileCandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 档案候选过期清理：每天一次把超过 {@code memory.candidate.expire.days} 天未处理的 pending 项
 * 标 expired（不是删除，行还在、可追溯）。
 *
 * <p>{@code @EnableScheduling} 由 async 模块全局提供，这里不重复开。
 * 失败只记 WARN：清理是后台维护，不能因为它把应用的健康检查搞红。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileCandidateExpiryJob {

    private final ProfileCandidateService profileCandidateService;

    @Scheduled(cron = "${guide.memory.candidate-expire-cron:0 20 3 * * *}")
    public void expire() {
        try {
            profileCandidateService.expireOverdue(LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("档案候选过期清理失败（下次心跳会再试）：{}", e.getMessage());
        }
    }
}
