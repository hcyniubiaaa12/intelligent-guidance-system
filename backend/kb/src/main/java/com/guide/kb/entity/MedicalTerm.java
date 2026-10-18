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
     * <p><b>文档删除时级联清理</b>：该文档带出来的术语随文档一起**物理删除**
     * （{@code MedicalTermService#deleteBySourceDoc}）——它们是派生数据，不是人的决定；
     * 逻辑删除会占着唯一键、让同一个词以后再也抽不回来。人工新增的词这里为 {@code null}，
     * 不受任何文档删除影响。管理端的「文档已删除」只剩老数据/悬空 id 的兜底显示。
     */
    private String docId;

    /** 1 启用 / 0 停用 */
    private Integer enabled;
}
