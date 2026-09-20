package com.guide.chat.service;

import com.guide.auth.entity.SensitiveWord;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.mapper.SensitiveWordMapper;
import com.guide.auth.service.UserViolationService;
import com.guide.chat.event.SensitiveHitEvent;
import com.guide.chat.port.SensitiveHitCounter;
import com.guide.common.config.PromptProperties;
import com.guide.kb.service.MedicalTermService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入口校验单测（chat）：禁言短路、白名单防误杀、按词次收集命中、先计数再落库。
 */
class SensitiveGuardTest {

    private static final String USER = "u1";
    private static final String SESSION = "s1";

    private SensitiveWordMapper wordMapper;
    private MedicalTermService termService;
    private ApplicationEventPublisher eventPublisher;
    private SensitiveHitCounter hitCounter;
    private UserViolationService violationStore;
    private SensitiveViolationService violationService;
    private PromptProperties prompts;
    private SensitiveGuard guard;

    @BeforeEach
    void setUp() {
        wordMapper = mock(SensitiveWordMapper.class);
        termService = mock(MedicalTermService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        hitCounter = mock(SensitiveHitCounter.class);
        violationStore = mock(UserViolationService.class);
        violationService = mock(SensitiveViolationService.class);
        prompts = new PromptProperties();
        prompts.getChat().setBlockedReply("请描述您的症状，我来帮您分诊。");
        prompts.getChat().setWarnReply("请注意用语。");
        prompts.getChat().setMuteReply("您的发言已被临时限制 {minutes} 分钟。");
        guard = new SensitiveGuard(wordMapper, termService, eventPublisher, prompts,
                hitCounter, violationStore, violationService);

        when(wordMapper.selectList(any())).thenReturn(List.of(word("w1", "骂人的话", SensitiveWordType.BANNED),
                word("w2", "广告", SensitiveWordType.WATCH)));
        when(hitCounter.countSince(any(), any())).thenReturn(SensitiveHitCounter.Counts.zero());
        when(violationService.currentRule()).thenReturn(
                new SensitiveViolationService.Rule(60, LocalDateTime.now().minusMinutes(60), 10, 30, 25, 60));
        when(violationService.apply(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(SensitiveViolationService.Outcome.none());
    }

    private SensitiveWord word(String id, String text, SensitiveWordType type) {
        SensitiveWord word = new SensitiveWord();
        word.setId(id);
        word.setWord(text);
        word.setType(type);
        word.setEnabled(1);
        return word;
    }

    @Test
    @DisplayName("禁言期内直接拦下：不查词库、不计命中（禁言期发言不该继续累计）")
    void mutedUserShortCircuits() {
        when(violationStore.muteUntil(USER)).thenReturn(Optional.of(LocalDateTime.now().plusMinutes(20)));
        when(violationStore.remainingMinutes(any())).thenReturn(20L);

        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "骂人的话");

        assertEquals(SensitiveGuard.GuardResult.Action.MUTED, result.action());
        assertEquals("您的发言已被临时限制 20 分钟。", result.reply());
        verify(wordMapper, never()).selectList(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(wordMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("命中禁止词：拦截 + 返回固定引导，落一条 blocked 命中")
    void blockedHit() {
        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "你这个人真骂人的话");

        assertEquals(SensitiveGuard.GuardResult.Action.BLOCKED, result.action());
        assertEquals("骂人的话", result.word());
        assertEquals("请描述您的症状，我来帮您分诊。", result.reply());

        ArgumentCaptor<SensitiveHitEvent> event = ArgumentCaptor.forClass(SensitiveHitEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(SensitiveHitEvent.BLOCKED, event.getValue().action());
        assertEquals("w1", event.getValue().wordId());
    }

    @Test
    @DisplayName("禁止词本身在医学术语白名单里：放行，且不计命中（防误杀，也防污染统计）")
    void whitelistedBannedWordPassesWithoutCounting() {
        when(termService.isTerm("骂人的话")).thenReturn(true);

        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "骂人的话");

        assertEquals(SensitiveGuard.GuardResult.Action.PASS, result.action());
        verify(eventPublisher, never()).publishEvent(any());
        verify(wordMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("命中观察词：放行 + 留痕，不拦截")
    void watchedHit() {
        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "给你们家广告点个赞");

        assertEquals(SensitiveGuard.GuardResult.Action.WATCHED, result.action());
        assertEquals("广告", result.word());
        assertNull(result.reply());

        ArgumentCaptor<SensitiveHitEvent> event = ArgumentCaptor.forClass(SensitiveHitEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(SensitiveHitEvent.WATCHED, event.getValue().action());
    }

    @Test
    @DisplayName("一条消息命中两个观察词算两次（累计口径是词次，不是消息数）")
    void countsEveryHitWord() {
        when(wordMapper.selectList(any())).thenReturn(List.of(
                word("w1", "广告", SensitiveWordType.WATCH),
                word("w2", "加微信", SensitiveWordType.WATCH)));

        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "广告位招租，加微信详聊");

        assertEquals(SensitiveGuard.GuardResult.Action.WATCHED, result.action());
        verify(eventPublisher, times(2)).publishEvent(any(SensitiveHitEvent.class));
    }

    @Test
    @DisplayName("禁止词与观察词同时命中：按更重的禁止词拦截，观察词照样留痕")
    void bannedTakesPrecedenceButWatchStillRecorded() {
        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "骂人的话，顺便打个广告");

        assertEquals(SensitiveGuard.GuardResult.Action.BLOCKED, result.action());
        verify(eventPublisher, times(2)).publishEvent(any(SensitiveHitEvent.class));
    }

    @Test
    @DisplayName("先取窗口计数、再落本轮命中：不依赖事件监听器的落库时序")
    void countsBeforeRecording() {
        guard.check(USER, SESSION, "打个广告");

        InOrder order = inOrder(hitCounter, eventPublisher);
        order.verify(hitCounter).countSince(any(), any());
        order.verify(eventPublisher).publishEvent(any(SensitiveHitEvent.class));
    }

    @Test
    @DisplayName("处置提示透传：拦截时也带上警告话术（拦截话术讲下一步，警告讲后果）")
    void noticeIsPassedThrough() {
        when(violationService.apply(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SensitiveViolationService.Outcome(false, null, "请注意用语。", 10));

        SensitiveGuard.GuardResult blocked = guard.check(USER, SESSION, "骂人的话");
        assertEquals("请注意用语。", blocked.notice());

        SensitiveGuard.GuardResult watched = guard.check(USER, SESSION, "打个广告");
        assertEquals("请注意用语。", watched.notice());
    }

    @Test
    @DisplayName("本轮跨过禁言线：立即改为禁言，返回禁言话术而不是拦截话术")
    void crossingMuteLineReturnsMuteReply() {
        LocalDateTime until = LocalDateTime.now().plusMinutes(60);
        when(violationService.apply(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SensitiveViolationService.Outcome(true, until, null, 30));
        when(violationStore.remainingMinutes(until)).thenReturn(60L);

        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "骂人的话");

        assertEquals(SensitiveGuard.GuardResult.Action.MUTED, result.action());
        assertEquals(until, result.muteUntil());
        assertTrue(result.reply().contains("60 分钟"));
    }

    @Test
    @DisplayName("词库无启用词时直接放行，不做多余查询")
    void emptyWordLibraryPasses() {
        when(wordMapper.selectList(any())).thenReturn(List.of());

        SensitiveGuard.GuardResult result = guard.check(USER, SESSION, "胸口闷三天了");

        assertEquals(SensitiveGuard.GuardResult.Action.PASS, result.action());
        verify(hitCounter, never()).countSince(any(), any());
    }
}
