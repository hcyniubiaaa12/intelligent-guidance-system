package com.guide.memory.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 档案候选状态（profile_candidate.status）。
 *
 * <p>{@code pending} = 待患者确认（唯一会出现在「待确认」段的状态）；
 * {@code accepted} = 已接受并写进档案；{@code rejected} = 已拒绝（同一值不再重复提议）；
 * {@code expired} = 30 天未处理自动失效（仍留库可追溯，但不再展示、不再提议）。
 */
@Getter
@RequiredArgsConstructor
public enum CandidateStatus {

    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    EXPIRED("expired");

    @EnumValue
    private final String code;
}
