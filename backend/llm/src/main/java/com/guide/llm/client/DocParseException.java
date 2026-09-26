package com.guide.llm.client;

import lombok.Getter;

/**
 * 文档解析失败。**带失败分类与人话原因**——分类决定管理端「重新处理」按钮给不给，
 * 人话是写给"要决定下一步做什么的人"看的。
 *
 * <p>三个字段、三种读者，别混（链路 B「失败分类」）：
 * {@code kind} 给**程序**、{@link #getHumanReason()} 给**要动手的人**、
 * {@code cause} 的原文给**要排查的人**（只进 {@code logs/guide.log}）。
 *
 * <p>上游异常原文里有 OSS 路径、STS token 片段、请求 id、内部错误码，**不能直通管理员**——
 * 做法沿用链路 A 修过的先例（异常原文白名单式映射）。
 */
@Getter
public class DocParseException extends RuntimeException {

    /** 判据压成一句：**再跑一次可能就好了** */
    public enum Kind {
        /** 外部依赖故障（超时 / 限流 / 欠费 / 5xx）→ 可重试 */
        DEPENDENCY,
        /** 输入本身有问题（加密 pdf / 损坏件 / 打不开）→ 不可重试 */
        INPUT
    }

    private final Kind kind;

    /** 写给要动手的人的一句话：说清原因与下一步 */
    private final String humanReason;

    public DocParseException(Kind kind, String humanReason, Throwable cause) {
        super(humanReason, cause);
        this.kind = kind;
        this.humanReason = humanReason;
    }

    public DocParseException(Kind kind, String humanReason) {
        this(kind, humanReason, null);
    }
}
