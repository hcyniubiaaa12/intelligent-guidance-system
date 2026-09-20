package com.guide.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.enums.ViolationLevel;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户违规处置留痕（敏感词按窗口累计后的警告/禁言）。
 *
 * <p>记的是**处置事实**（当时窗口内命中多少词次、对着哪个阈值、窗口多长），而不是只记一个结果：
 * 阈值是管理端可调的，事后追溯一条历史处置时必须能复现当时的口径。
 *
 * <p>用途有两个：管理端用户管理的展示与追溯；以及「同一窗口内不重复告警」的判据
 * （否则用户一到 10 次，之后每一轮都会再被警告一次）。
 */
@Getter
@Setter
@TableName("user_violation")
public class UserViolation extends BaseEntity {

    private String userId;

    private ViolationLevel level;

    /** 触发命中的词类型：banned（禁止词）/ watch（观察词） */
    private SensitiveWordType hitType;

    /** 判定时窗口内的命中词次（含触发本轮） */
    private Integer hitCount;

    /** 统计窗口（分钟） */
    private Integer windowMinutes;

    /** 触发阈值（当时生效值） */
    private Integer threshold;

    /** 禁言截止（level=mute 时与 user.mute_until 同值） */
    private LocalDateTime muteUntil;

    /** 处置时间 */
    private LocalDateTime occurredAt;
}
