package com.guide.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.common.entity.BaseEntity;
import com.guide.kb.enums.TermSource;
import com.guide.kb.enums.TermType;
import lombok.Getter;
import lombok.Setter;

/**
 * 白名单（4.3）：唯一生效源，chat 入口加载内存 Set 一律取自此表；候选由入库抽取直接写本表。
 * LLM 抽取新术语落库默认 enabled=0，受"人工审核"开关控制；管理端增删/停用即时影响线上校验。
 */
@Getter
@Setter
@TableName("medical_term")
public class MedicalTerm extends BaseEntity {

    private String term;

    private TermType type;

    private TermSource source;

    /** 1 启用 / 0 停用 */
    private Integer enabled;
}
