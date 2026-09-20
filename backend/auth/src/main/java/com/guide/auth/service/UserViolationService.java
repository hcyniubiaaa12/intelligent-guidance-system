package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.entity.User;
import com.guide.auth.entity.UserViolation;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.enums.ViolationLevel;
import com.guide.auth.mapper.UserMapper;
import com.guide.auth.mapper.UserViolationMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 用户违规处置的落库原语（auth）：禁言状态读写、警告/禁言留痕、窗口内是否已告警。
 *
 * <p>**判定不在这里**：「窗口内命中多少词次、对着哪个阈值该警告还是禁言」是链路 A 入口校验的语义，
 * 由 chat 侧判定（阈值也来自 chat 使用的 sys_config）。本服务只负责两件事的性质——
 * 禁言状态（{@code user.mute_until}）与处置留痕（{@code user_violation}），二者一起归 auth 管。
 *
 * <p>依赖方向：chat → auth（chat 已有此方向的先例，如敏感词库与 sys_config），不反向。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserViolationService {

    private final UserMapper userMapper;
    private final UserViolationMapper violationMapper;

    /**
     * 禁言状态：未禁言或已到期返回 {@link Optional#empty()}。
     *
     * <p>没有定时解除任务——到期判断就是「当前时间是否超过 mute_until」，
     * 少一个需要保证准时运行的组件（定时任务停摆会导致禁言永久生效，这种故障很难被发现）。
     */
    public Optional<LocalDateTime> muteUntil(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getMuteUntil() == null) {
            return Optional.empty();
        }
        return user.getMuteUntil().isAfter(LocalDateTime.now())
                ? Optional.of(user.getMuteUntil())
                : Optional.empty();
    }

    /** 剩余禁言分钟数（向上取整，至少 1；不足一分钟时返回 1 而不是 0） */
    public long remainingMinutes(LocalDateTime until) {
        long minutes = Duration.between(LocalDateTime.now(), until).toMinutes();
        return Math.max(1, minutes);
    }

    /** 管理端立即解除禁言：返回是否本来就处于禁言中（用于前端提示语义） */
    @Transactional
    public boolean unmute(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        boolean muted = user.getMuteUntil() != null && user.getMuteUntil().isAfter(LocalDateTime.now());
        if (muted) {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .set(User::getMuteUntil, null)
                    .eq(User::getId, userId));
        }
        return muted;
    }

    /** 窗口内是否已对这类词告警过——同一窗口不重复念叨，否则一到阈值每轮都会被警告一次 */
    public boolean warnedWithin(String userId, SensitiveWordType hitType, LocalDateTime since) {
        return countWithin(userId, ViolationLevel.WARN, hitType, since) > 0;
    }

    /**
     * 窗口内是否已禁言过这类词——同一窗口只禁一次。
     *
     * <p>为什么需要它：禁言线是"窗口内累计次数"，而次数不会因为一次禁言而清零。
     * 若不判重，管理员「立即解除禁言」后用户下一条消息就会被重新禁言（计数仍超线），
     * 解除操作形同虚设、且禁言期会被自己的旧计数不断续上。
     */
    public boolean mutedWithin(String userId, SensitiveWordType hitType, LocalDateTime since) {
        return countWithin(userId, ViolationLevel.MUTE, hitType, since) > 0;
    }

    private long countWithin(String userId, ViolationLevel level, SensitiveWordType hitType,
                             LocalDateTime since) {
        return violationMapper.selectCount(Wrappers.<UserViolation>lambdaQuery()
                .eq(UserViolation::getUserId, userId)
                .eq(UserViolation::getLevel, level)
                .eq(UserViolation::getHitType, hitType)
                .ge(UserViolation::getOccurredAt, since));
    }

    /** 记录一次警告 */
    public void warn(String userId, SensitiveWordType hitType, int hitCount,
                     int windowMinutes, int threshold, LocalDateTime occurredAt) {
        insert(userId, ViolationLevel.WARN, hitType, hitCount, windowMinutes, threshold, null, occurredAt);
        log.info("敏感词处置：用户 {} 警告（{} 词在 {} 分钟内命中 {} 次，阈值 {}）",
                userId, hitType.getCode(), windowMinutes, hitCount, threshold);
    }

    /**
     * 记录一次禁言并写入 {@code user.mute_until}。
     *
     * <p>两处写在同一事务里：先落状态再写留痕的话，中途失败会出现「用户已被禁言但查不到为什么」。
     */
    @Transactional
    public void mute(String userId, SensitiveWordType hitType, int hitCount,
                     int windowMinutes, int threshold, LocalDateTime until, LocalDateTime occurredAt) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getMuteUntil, until)
                .eq(User::getId, userId));
        insert(userId, ViolationLevel.MUTE, hitType, hitCount, windowMinutes, threshold, until, occurredAt);
        log.info("敏感词处置：用户 {} 禁言至 {}（{} 词在 {} 分钟内命中 {} 次，阈值 {}）",
                userId, until, hitType.getCode(), windowMinutes, hitCount, threshold);
    }

    /** 指定用户在某时间点之后的处置记录（管理端展示；按时间倒序） */
    public List<UserViolation> listSince(Collection<String> userIds, LocalDateTime since) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        return violationMapper.selectList(Wrappers.<UserViolation>lambdaQuery()
                .in(UserViolation::getUserId, userIds)
                .ge(UserViolation::getOccurredAt, since)
                .orderByDesc(UserViolation::getOccurredAt));
    }

    private void insert(String userId, ViolationLevel level, SensitiveWordType hitType, int hitCount,
                        int windowMinutes, int threshold, LocalDateTime until, LocalDateTime occurredAt) {
        UserViolation violation = new UserViolation();
        violation.setUserId(userId);
        violation.setLevel(level);
        violation.setHitType(hitType);
        violation.setHitCount(hitCount);
        violation.setWindowMinutes(windowMinutes);
        violation.setThreshold(threshold);
        violation.setMuteUntil(until);
        violation.setOccurredAt(occurredAt);
        violationMapper.insert(violation);
    }
}
