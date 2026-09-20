package com.guide.chat.service;

import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.service.UserViolationService;
import com.guide.chat.port.SensitiveHitCounter;
import com.guide.common.config.PromptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感词累计处置判定单测（chat）：什么时候警告、什么时候禁言、什么时候什么都不做。
 *
 * <p>这是"用户被处置"的唯一决策点，几条容易写错的边界都在这里钉住：
 * 阈值是 **≥**（不是 >）、禁言优先于警告、同一窗口只告警一次、观察词**永不**禁言。
 */
class SensitiveViolationServiceTest {

    private static final String USER = "u1";

    private SysConfigService sysConfigService;
    private UserViolationService violationStore;
    private SensitiveViolationService service;

    @BeforeEach
    void setUp() {
        sysConfigService = mock(SysConfigService.class);
        violationStore = mock(UserViolationService.class);
        PromptProperties prompts = new PromptProperties();
        prompts.getChat().setWarnReply("请注意用语。");
        // 默认配置：窗口 60 分钟、禁止词 10 警告 30 禁言、观察词 25 警告、禁言 60 分钟
        when(sysConfigService.getInt(anyString(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return switch (key) {
                case SysConfigService.KEY_SENSITIVE_WINDOW_MINUTES -> 60;
                case SysConfigService.KEY_SENSITIVE_BANNED_WARN -> 10;
                case SysConfigService.KEY_SENSITIVE_BANNED_MUTE -> 30;
                case SysConfigService.KEY_SENSITIVE_WATCH_WARN -> 25;
                case SysConfigService.KEY_SENSITIVE_MUTE_MINUTES -> 60;
                default -> invocation.getArgument(1);
            };
        });
        service = new SensitiveViolationService(sysConfigService, violationStore, prompts);
    }

    private SensitiveViolationService.Rule rule() {
        return service.currentRule();
    }

    @Test
    @DisplayName("规则快照带上窗口长度与起点（滑动窗口，不做自然小时对齐）")
    void ruleCarriesWindow() {
        SensitiveViolationService.Rule rule = rule();

        assertEquals(60, rule.windowMinutes());
        assertEquals(10, rule.bannedWarnCount());
        assertEquals(30, rule.bannedMuteCount());
        assertEquals(25, rule.watchWarnCount());
        assertEquals(60, rule.muteMinutes());
        assertTrue(rule.since().isBefore(LocalDateTime.now().minusMinutes(59)));
    }

    @Test
    @DisplayName("禁止词没到警告线：什么都不做（不留痕、不提示）")
    void belowWarnLineIsSilent() {
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(8, 0), 1, 0);

        assertFalse(outcome.muted());
        assertNull(outcome.notice());
        verify(violationStore, never()).warn(any(), any(), anyInt(), anyInt(), anyInt(), any());
        verify(violationStore, never()).mute(any(), any(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("禁止词恰好到警告线（≥ 而非 >）：警告一次并给出提示话术")
    void warnAtExactLine() {
        // 窗口内已有 9 次，本轮再命中 1 次 = 10
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(9, 0), 1, 0);

        assertFalse(outcome.muted());
        assertEquals("请注意用语。", outcome.notice());
        assertEquals(10, outcome.hitCount());
        ArgumentCaptor<Integer> threshold = ArgumentCaptor.forClass(Integer.class);
        verify(violationStore).warn(eq(USER), eq(SensitiveWordType.BANNED), eq(10), eq(60),
                threshold.capture(), any());
        assertEquals(10, threshold.getValue());
    }

    @Test
    @DisplayName("同一窗口内已告警过就不再念叨（否则用户一过阈值每轮都被警告）")
    void warnIsIdempotentWithinWindow() {
        when(violationStore.warnedWithin(eq(USER), eq(SensitiveWordType.BANNED), any()))
                .thenReturn(true);

        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(12, 0), 1, 0);

        assertNull(outcome.notice());
        verify(violationStore, never()).warn(any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("禁止词跨过禁言线：直接禁言，并不再补一条警告（已经在处置了）")
    void muteWinsOverWarn() {
        LocalDateTime before = LocalDateTime.now();

        // 窗口内 29 + 本轮 1 = 30
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(29, 0), 1, 0);

        assertTrue(outcome.muted());
        assertEquals(30, outcome.hitCount());
        assertNotNull(outcome.muteUntil());
        assertTrue(outcome.muteUntil().isAfter(before.plusMinutes(59)));
        assertNull(outcome.notice(), "跨禁言线时不再追加警告话术");
        verify(violationStore).mute(eq(USER), eq(SensitiveWordType.BANNED), eq(30), eq(60), eq(30),
                any(), any());
        verify(violationStore, never()).warn(any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("窗口内已禁言过就不再禁：否则管理员「立即解除」后下一条消息会被旧计数重新禁上")
    void muteIsIdempotentWithinWindow() {
        when(violationStore.mutedWithin(eq(USER), eq(SensitiveWordType.BANNED), any()))
                .thenReturn(true);

        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(29, 0), 1, 0);

        assertFalse(outcome.muted());
        verify(violationStore, never()).mute(any(), any(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("观察词到线只警告、永不禁言（观察词本就是放行观察用的）")
    void watchOnlyWarns() {
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(0, 24), 0, 1);

        assertFalse(outcome.muted());
        assertEquals("请注意用语。", outcome.notice());
        verify(violationStore).warn(eq(USER), eq(SensitiveWordType.WATCH), eq(25), eq(60), eq(25), any());
        verify(violationStore, never()).mute(any(), any(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("观察词不到线：静默放行")
    void watchBelowLineIsSilent() {
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(0, 5), 0, 3);

        assertFalse(outcome.muted());
        assertNull(outcome.notice());
        verify(violationStore, never()).warn(any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("禁止词到线优先于观察词到线：只处置一次，按更重的禁止词记")
    void bannedTakesPrecedenceOverWatch() {
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(9, 24), 1, 1);

        assertEquals("请注意用语。", outcome.notice());
        verify(violationStore).warn(eq(USER), eq(SensitiveWordType.BANNED), eq(10), eq(60), eq(10), any());
        verify(violationStore, never()).warn(eq(USER), eq(SensitiveWordType.WATCH), anyInt(), anyInt(),
                anyInt(), any());
    }

    @Test
    @DisplayName("本轮词次要计入判定（窗口内 9 次 + 本轮 2 次 = 11 次，已过警告线）")
    void currentTurnHitsAreCounted() {
        SensitiveViolationService.Outcome outcome =
                service.apply(USER, rule(), new SensitiveHitCounter.Counts(9, 0), 2, 0);

        assertEquals(11, outcome.hitCount());
        assertNotNull(outcome.notice());
    }
}
