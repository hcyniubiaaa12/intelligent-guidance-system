package com.guide.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.entity.SensitiveWord;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.mapper.SensitiveWordMapper;
import com.guide.auth.service.UserViolationService;
import com.guide.chat.event.SensitiveHitEvent;
import com.guide.chat.port.SensitiveHitCounter;
import com.guide.common.config.PromptProperties;
import com.guide.kb.service.MedicalTermService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 敏感词入口前置校验（链路 A 的 6.1）与按用户累计处置（6.6）。
 *
 * <p>医院导诊的敏感词与通用内容审核不同：部位/症状名词（胸、腹、下体等）是分诊核心信息，不能拦；
 * 真正要拦的是过激言论与辱骂。「疼死了」「要死了」是真实痛苦表达，不拦。
 *
 * <p>判定口径（对齐 6.1 的"白名单优先、宁可少拦"）：命中的禁止词**本身**是医学术语白名单里的词 →
 * 放行（防误杀）；否则拦截。这样既不会因主诉里出现"胸"而漏掉脏话，也不会因词库里混入部位词而误杀主诉。
 *
 * <p>词库实时查表（表小且带索引），不缓存不落 Redis——管理端增删停用即时生效。
 *
 * <p>分工：本类只做「入口判定 + 命中落库 + 触发处置」；阈值口径在 {@link SensitiveViolationService}，
 * 禁言状态与处置留痕在 auth 的 {@code UserViolationService}，窗口计数由 {@link SensitiveHitCounter} 端口提供。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveGuard {

    private final SensitiveWordMapper sensitiveWordMapper;
    private final MedicalTermService medicalTermService;
    private final ApplicationEventPublisher eventPublisher;
    private final PromptProperties prompts;
    private final SensitiveHitCounter hitCounter;
    private final UserViolationService userViolationService;
    private final SensitiveViolationService violationService;

    public GuardResult check(String userId, String sessionId, String content) {
        if (content == null || content.isBlank()) {
            return GuardResult.pass();
        }

        // ⓪ 禁言期内：直接拦下，不匹配词库、不计违规。
        // 为什么禁言期不计违规：否则用户随便发几句就会把自己再禁一次，禁言期滚动延长、永远出不来
        Optional<LocalDateTime> muted = userViolationService.muteUntil(userId);
        if (muted.isPresent()) {
            long minutes = userViolationService.remainingMinutes(muted.get());
            log.info("入口校验：用户处于禁言期（至 {}，约剩 {} 分钟），本轮不进入导诊", muted.get(), minutes);
            return GuardResult.muted(muted.get(), renderMuteReply(minutes));
        }

        List<SensitiveWord> words = sensitiveWordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getEnabled, 1));
        if (words.isEmpty()) {
            log.debug("入口校验：词库无启用词，直接放行");
            return GuardResult.pass();
        }
        log.debug("入口校验：加载启用词 {} 条（禁止 {} / 观察 {}）", words.size(),
                words.stream().filter(w -> w.getType() == SensitiveWordType.BANNED).count(),
                words.stream().filter(w -> w.getType() == SensitiveWordType.WATCH).count());

        // ① 匹配：**收集全部命中**，不遇到第一个禁止词就返回——
        // 累计口径是「词次」，只记第一个会让次数明显偏低、阈值永远够不到
        List<SensitiveWord> bannedHits = new ArrayList<>();
        List<SensitiveWord> watchHits = new ArrayList<>();
        for (SensitiveWord word : words) {
            if (word.getWord() == null || !content.contains(word.getWord())) {
                continue;
            }
            if (word.getType() == SensitiveWordType.WATCH) {
                watchHits.add(word);
                continue;
            }
            if (medicalTermService.isTerm(word.getWord())) {
                // 白名单优先：该词本身是医学术语（部位/症状），防误杀放行。
                // 精确匹配，不能用子串——白名单含「心/头/手」等单字，子串判定会让「黑心医院」漏拦。
                // 也不计命中：它本就不该在词库里，记了会污染命中统计与阈值判定
                log.debug("入口校验：禁止词「{}」在医学术语白名单内，放行且不计命中", word.getWord());
                continue;
            }
            bannedHits.add(word);
        }
        if (bannedHits.isEmpty() && watchHits.isEmpty()) {
            return GuardResult.pass();
        }

        // ② 规则快照只读一次：窗口与阈值取自同一份配置，避免判定中途被管理端改参数而口径漂移
        SensitiveViolationService.Rule rule = violationService.currentRule();
        // ③ 先取窗口内已发生的计数（不含本轮），再落本轮命中。
        // 顺序刻意如此：不依赖事件监听器的落库时序，本轮词次直接相加即可
        SensitiveHitCounter.Counts before = hitCounter.countSince(userId, rule.since());
        bannedHits.forEach(word -> recordHit(userId, sessionId, word, SensitiveHitEvent.BLOCKED));
        watchHits.forEach(word -> recordHit(userId, sessionId, word, SensitiveHitEvent.WATCHED));

        // ④ 判定与处置
        SensitiveViolationService.Outcome outcome =
                violationService.apply(userId, rule, before, bannedHits.size(), watchHits.size());
        if (outcome.muted()) {
            long minutes = userViolationService.remainingMinutes(outcome.muteUntil());
            log.info("入口校验：禁止词累计跨过禁言线（{} 次），禁言至 {}，本轮不进入导诊",
                    outcome.hitCount(), outcome.muteUntil());
            return GuardResult.muted(outcome.muteUntil(), renderMuteReply(minutes));
        }
        if (!bannedHits.isEmpty()) {
            log.info("入口校验：命中禁止词 {} 个 {}，拦截", bannedHits.size(),
                    bannedHits.stream().map(SensitiveWord::getWord).toList());
            return GuardResult.blocked(bannedHits.get(0).getWord(),
                    prompts.getChat().getBlockedReply(), outcome.notice());
        }
        log.info("入口校验：命中观察词 {} 个 {}，放行并留痕", watchHits.size(),
                watchHits.stream().map(SensitiveWord::getWord).toList());
        return GuardResult.watched(watchHits.get(0).getWord(), outcome.notice());
    }

    /** 禁言话术：把 {minutes} 替换成实际分钟数（漏了占位符会被 PromptProperties 启动校验拦住） */
    private String renderMuteReply(long minutes) {
        return PromptProperties.render(prompts.getChat().getMuteReply(),
                PromptProperties.PLACEHOLDER_MINUTES, String.valueOf(minutes));
    }

    /** 命中次数累加 + 旁路日志事件；任何失败都不影响主流程 */
    private void recordHit(String userId, String sessionId, SensitiveWord word, String action) {
        try {
            sensitiveWordMapper.update(null, Wrappers.<SensitiveWord>lambdaUpdate()
                    .setSql("hit_count = hit_count + 1")
                    .eq(SensitiveWord::getId, word.getId()));
            eventPublisher.publishEvent(new SensitiveHitEvent(userId, sessionId, word.getId(), action));
        } catch (Exception e) {
            log.warn("敏感词命中留痕失败（不影响导诊）：{}", e.getMessage());
        }
    }

    /**
     * 入口校验结果。
     *
     * @param action    本轮动作
     * @param word      触发词（放行时为 null）
     * @param reply     要发给患者的话术（拦截与禁言才有；调用方落库并出气泡）
     * @param notice    处置提示（警告）——**不阻断本轮**，作为额外一条气泡发出，可为 null
     * @param muteUntil 禁言截止（action=MUTED 才有）
     */
    public record GuardResult(Action action, String word, String reply, String notice,
                              LocalDateTime muteUntil) {

        public enum Action {
            /** 放行 */
            PASS,
            /** 命中观察词：放行 + 留痕 */
            WATCHED,
            /** 命中禁止词：拦截 */
            BLOCKED,
            /** 处于禁言期：本轮不进入导诊 */
            MUTED
        }

        static GuardResult pass() {
            return new GuardResult(Action.PASS, null, null, null, null);
        }

        static GuardResult watched(String word, String notice) {
            return new GuardResult(Action.WATCHED, word, null, notice, null);
        }

        static GuardResult blocked(String word, String reply, String notice) {
            return new GuardResult(Action.BLOCKED, word, reply, notice, null);
        }

        static GuardResult muted(LocalDateTime until, String reply) {
            return new GuardResult(Action.MUTED, null, reply, null, until);
        }
    }
}
