package com.guide.common.util;

import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chunk 索引 mapping 的术语字段（{@link EsChunkUtil#chunkIndexMapping()}）。
 *
 * <p><b>为什么必须钉住</b>：该字段原先的 {@code keyword} 类型在 {@code match} 查询里匹配不上中文术语，
 * 加分等于没加；而 ES 不允许原地改字段类型，写错了既不会让启动失败、也不会让检索报错，
 * 只会让「术语加分」静默失效。重建检测（{@link EsChunkUtil#termMappingNeedsRebuild}）
 * 把「旧索引要人工重建」从注释变成一条可观测的 WARN。
 */
class EsChunkUtilMappingTest {

    @Test
    @DisplayName("medical_terms 是 text（ik_max_word / ik_smart）且带 .keyword 子字段")
    void medicalTermsIsAnalyzedTextWithKeywordSubfield() {
        TypeMapping mapping = EsChunkUtil.chunkIndexMapping();
        Property terms = mapping.properties().get("medical_terms");
        assertNotNull(terms);
        assertTrue(terms.isText());
        assertEquals("ik_max_word", terms.text().analyzer());
        assertEquals("ik_smart", terms.text().searchAnalyzer());
        Property keywordSub = terms.text().fields().get("keyword");
        assertNotNull(keywordSub, "留精确匹配与将来聚合的入口");
        assertTrue(keywordSub.isKeyword());
    }

    @Test
    @DisplayName("其余字段保持原样：chunk_id/dept_id/chunk_type 是 keyword，title/content 是 ik text")
    void otherFieldsUnchanged() {
        TypeMapping mapping = EsChunkUtil.chunkIndexMapping();
        assertTrue(mapping.properties().get("chunk_id").isKeyword());
        assertTrue(mapping.properties().get("dept_id").isKeyword());
        assertTrue(mapping.properties().get("chunk_type").isKeyword());
        assertEquals("ik_max_word", mapping.properties().get("title").text().analyzer());
        assertEquals("ik_smart", mapping.properties().get("title").text().searchAnalyzer());
        assertEquals("ik_max_word", mapping.properties().get("content").text().analyzer());
        assertEquals("ik_smart", mapping.properties().get("content").text().searchAnalyzer());
    }

    @Test
    @DisplayName("旧 mapping（medical_terms=keyword）被判为需重建；已是 text / 字段缺失则不报")
    void rebuildIsRequiredOnlyWhenTermFieldIsNotText() {
        TypeMapping oldKeyword = TypeMapping.of(m -> m
                .properties("medical_terms", p -> p.keyword(k -> k)));
        assertTrue(EsChunkUtil.termMappingNeedsRebuild(oldKeyword));

        TypeMapping current = EsChunkUtil.chunkIndexMapping();
        assertFalse(EsChunkUtil.termMappingNeedsRebuild(current));

        // 更老的索引没有该字段：putMapping 能补，不算"需重建"
        TypeMapping noTerms = TypeMapping.of(m -> m
                .properties("chunk_type", p -> p.keyword(k -> k)));
        assertFalse(EsChunkUtil.termMappingNeedsRebuild(noTerms));
        assertFalse(EsChunkUtil.termMappingNeedsRebuild(null));
    }
}
