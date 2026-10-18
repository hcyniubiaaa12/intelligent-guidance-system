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

    /**
     * 首次抽到该词的入库文档（{@code source=llm_extract} 时有值，人工新增为 {@code null}）。
     *
     * <p><b>只记「第一次」</b>：同一个词被后续文档再次抽到时走"跳过活跃行"，来源不覆盖——
     * 这个字段回答的是"这个词是从哪份文档开始出现的"，不是"所有出现过的文档"。
     * 要后者得建关联表，而管理端只需要前者来判断可信度。
     *
     * <p>文档被删后这里会留下悬空 id（术语不随文档删除），管理端显示「文档已删除」。
     */
    private String docId;

    /** 1 启用 / 0 停用 */
    private Integer enabled;
}
