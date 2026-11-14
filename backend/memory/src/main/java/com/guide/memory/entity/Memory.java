package com.guide.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.common.entity.BaseEntity;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 长期记忆（user_memory）：用户级、跨会话的偏好与稳定背景。
 *
 * <p>来源是模型从对话里抽取（免确认、可删），每条都带 {@link #sourceQuote} 患者原话——
 * 「记错了还一直用」在医疗场景比「没记住」更危险，所以必须可追溯。
 *
 * <p>{@link #retrievalText} 只供 embedding 扩展，不注入 prompt；
 * {@link #validFrom} / {@link #validTo} 是有效期（空 = 无期限）。
 */
@Getter
@Setter
@TableName("user_memory")
public class Memory extends BaseEntity {

    private String userId;

    private MemoryType type;

    /** 记忆键（≤8 字）：如「回答方式」「慢病」 */
    private String mkey;

    /** 记忆值（≤60 字）：如「讲慢一点」「2型糖尿病」 */
    private String mvalue;

    /** 供 embedding 的扩展文本，不注入 */
    private String retrievalText;

    /** 患者原话出处（抽取依据） */
    private String sourceQuote;

    /** 抽取置信度 0–1 */
    private BigDecimal confidence;

    /** 观察时间：这条事实从哪一刻起被记住 */
    private LocalDateTime observedAt;

    private LocalDate validFrom;

    private LocalDate validTo;

    private MemoryStatus status;
}
