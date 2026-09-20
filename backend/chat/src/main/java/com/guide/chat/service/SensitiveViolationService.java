package com.guide.chat.service;

import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.service.UserViolationService;
import com.guide.chat.port.SensitiveHitCounter;
import com.guide.common.config.PromptProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 敏感词累计处置的**判定**（链路 A 6.6）：窗口内命中词次跨过阈值时警告或禁言。
 *
 * <p>为什么判定在 chat 而不在 auth：阈值语义属于入口校验（哪些话术、什么时候拦），
 * auth 只管状态与留痕（{@code user.mute_until} / {@code user_violation}）。
 *
 * <p>三条口径：
 * <ul>
 *   <li><b>滑动窗口</b>：只看最近 N 分钟内的命中词次，不做自然小时对齐——"1 小时以内触发 10 次"说的就是滑动</li>
 *   <li><b>词次而非消息数</b>：一条消息命中两个观察词算两次（见 {@code SensitiveHitCounter}）</li>
 *   <li><b>同一窗口内同类处置只做一次</b>：警告到阈值那一刻提示一次即可，之后每轮再念叨只是骚扰；
 *       禁言同理——否则管理员「立即解除禁言」后，用户下一条消息会被仍超线的旧计数重新禁上，
 *       解除操作形同虚设（判据都是 {@code user_violation} 里窗口内已有同类记录）</li>
 * </ul>
 *
 * <p>禁言优先于警告：跨过禁言线就直接禁言，不再补一条警告——用户已经在被处置了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveViolationService {

    /** 配置缺失时的兜底（与 SysConfigAdminService 白名单默认值一致） */
    private static final int DEFAULT_WINDOW_MINUTES = 60;
    private static final int DEFAULT_BANNED_WARN = 10;
    private static final int DEFAULT_BANNED_MUTE = 30;
    private static final int DEFAULT_WATCH_WARN = 25;
    private static final int DEFAULT_MUTE_MINUTES = 60;

    private final SysConfigService sysConfigService;
    private final UserViolationService userViolationService;
    private final PromptProperties prompts;

    /**
     * 本轮判定用的规则快照：窗口（长度 + 起点）与三个阈值 + 禁言时长。
     *
     * <p>读一次就固定下来，是因为判定与落库之间还有几步；中途管理端改了参数不该让同一次判定
     * 用两套口径（比如按 60 分钟统计、却按新的 30 分钟记留痕）。
     */
    public record Rule(int windowMinutes, LocalDateTime since, int bannedWarnCount,
                       int bannedMuteCount, int watchWarnCount, int muteMinutes) {
    }

    /**
     * 处置结果。
     *
     * @param muted     是否已禁言
     * @param muteUntil 禁言截止（未禁言为 null）
     * @param notice    警告话术（未告警为 null）
     * @param hitCount  触发时的窗口内命中词次（留痕参考）
     */
    public record Outcome(boolean muted, LocalDateTime muteUntil, String notice, int hitCount) {

        static Outcome none() {
            return new Outcome(false, null, null, 0);
        }
    }

    public Rule currentRule() {
        int windowMinutes = Math.max(1,
                sysConfigService.getInt(SysConfigService.KEY_SENSITIVE_WINDOW_MINUTES, DEFAULT_WINDOW_MINUTES));
        int bannedWarn = sysConfigService.getInt(SysConfigService.KEY_SENSITIVE_BANNED_WARN, DEFAULT_BANNED_WARN);
        int bannedMute = sysConfigService.getInt(SysConfigService.KEY_SENSITIVE_BANNED_MUTE, DEFAULT_BANNED_MUTE);
        int watchWarn = sysConfigService.getInt(SysConfigService.KEY_SENSITIVE_WATCH_WARN, DEFAULT_WATCH_WARN);
        int muteMinutes = Math.max(1,
                sysConfigService.getInt(SysConfigService.KEY_SENSITIVE_MUTE_MINUTES, DEFAULT_MUTE_MINUTES));
        if (bannedMute <= bannedWarn) {
            // 管理端保存时已拦这种组合；能走到这里说明是直接改了库。
            // 判定顺序是「先看禁言线」，于是警告档永远不会触发——页面一切正常，只是警告没了
            log.warn("敏感词阈值配置异常：禁言阈值 {} 不大于警告阈值 {}，警告将永远不会触发（检查 sys_config）",
                    bannedMute, bannedWarn);
        }
        return new Rule(windowMinutes, LocalDateTime.now().minusMinutes(windowMinutes),
                bannedWarn, bannedMute, watchWarn, muteMinutes);
    }

    /**
     * 处置：禁止词跨禁言线 → 禁言；否则跨警告线 → 警告；观察词只到警告线就警告（**不禁言**）。
     *
     * @param before      本轮之前窗口内的命中词次
     * @param bannedHits  本轮禁止词命中词次
     * @param watchHits   本轮观察词命中词次
     */
    public Outcome apply(String userId, Rule rule, SensitiveHitCounter.Counts before,
                         int bannedHits, int watchHits) {
        long bannedTotal = before.banned() + bannedHits;
        long watchTotal = before.watch() + watchHits;
        LocalDateTime now = LocalDateTime.now();

        if (bannedTotal >= rule.bannedMuteCount()
                && !userViolationService.mutedWithin(userId, SensitiveWordType.BANNED, rule.since())) {
            LocalDateTime until = now.plusMinutes(rule.muteMinutes());
            userViolationService.mute(userId, SensitiveWordType.BANNED, (int) bannedTotal,
                    rule.windowMinutes(), rule.bannedMuteCount(), until, now);
            return new Outcome(true, until, null, (int) bannedTotal);
        }
        if (bannedTotal >= rule.bannedWarnCount()
                && !userViolationService.warnedWithin(userId, SensitiveWordType.BANNED, rule.since())) {
            userViolationService.warn(userId, SensitiveWordType.BANNED, (int) bannedTotal,
                    rule.windowMinutes(), rule.bannedWarnCount(), now);
            return new Outcome(false, null, prompts.getChat().getWarnReply(), (int) bannedTotal);
        }
        if (watchTotal >= rule.watchWarnCount()
                && !userViolationService.warnedWithin(userId, SensitiveWordType.WATCH, rule.since())) {
            userViolationService.warn(userId, SensitiveWordType.WATCH, (int) watchTotal,
                    rule.windowMinutes(), rule.watchWarnCount(), now);
            return new Outcome(false, null, prompts.getChat().getWarnReply(), (int) watchTotal);
        }
        return Outcome.none();
    }
}
