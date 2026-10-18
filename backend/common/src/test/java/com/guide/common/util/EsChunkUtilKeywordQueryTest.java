package com.guide.common.util;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词通道给术语加分（{@link EsChunkUtil#keywordQuery} 的查询形状）。
 *
 * <p>为什么钉形状：加分是否真的让排序变好，单测证明不了（要真实语料对比）；
 * 但**召回范围**能不能被悄悄改掉，单测能钉住。这里的断言全是字面量——
 * 把 {@code medical_terms} 挪回 {@code must}、把 {@code should} 改成强制命中、
 * 或删掉标题片过滤，都不会编译失败、也不会让任何接口报错，只会让召回慢慢跑偏。
 *
 * <p>不引入 Testcontainers / 内嵌 ES：`EsChunkUtil` 直接持有 `ElasticsearchClient`，
 * mock 它的收益低于成本；查询构造抽成包内可见纯函数后，这一条测试缝就够了。
 */
class EsChunkUtilKeywordQueryTest {

    @Test
    @DisplayName("must 只做 title^2 + content 的 multi_match，不把 medical_terms 塞进去")
    void mustOnlyMatchesTitleAndContent() {
        Query query = EsChunkUtil.keywordQuery("胸闷");

        assertTrue(query.isBool());
        List<Query> must = query.bool().must();
        assertEquals(1, must.size());
        Query clause = must.get(0);
        assertTrue(clause.isMultiMatch());
        assertEquals("胸闷", clause.multiMatch().query());
        assertEquals(List.of("title^2", "content"), clause.multiMatch().fields());
        // best_fields 是"取最高分字段"，术语多的切片会把标题信号顶掉——术语只能走 should
        assertFalse(must.stream().anyMatch(q -> q.isMatch() && "medical_terms".equals(q.match().field())));
    }

    @Test
    @DisplayName("should 是一条 match(medical_terms) 且 boost 恰为 1.0")
    void shouldBoostsMedicalTermsAtOne() {
        Query query = EsChunkUtil.keywordQuery("胸闷");

        List<Query> should = query.bool().should();
        assertEquals(1, should.size());
        Query clause = should.get(0);
        assertTrue(clause.isMatch());
        assertEquals("medical_terms", clause.match().field());
        assertEquals("胸闷", clause.match().query().stringValue());
        assertNotNull(clause.match().boost());
        assertEquals(1.0f, clause.match().boost());
        // 默认 minimum_should_match = 0：不改变原有召回集，命中才加分
        assertNull(query.bool().minimumShouldMatch());
    }

    @Test
    @DisplayName("mustNot 仍排除标题片（既有 ChunkRetrievalFilter，不回退）")
    void mustNotStillExcludesTitleChunks() {
        Query query = EsChunkUtil.keywordQuery("胸闷");

        List<Query> mustNot = query.bool().mustNot();
        assertEquals(1, mustNot.size());
        Query clause = mustNot.get(0);
        assertTrue(clause.isTerm());
        assertEquals("chunk_type", clause.term().field());
        assertEquals(ChunkRetrievalFilter.excludedCode(), clause.term().value().stringValue());
    }

    @Test
    @DisplayName("空查询不抛，构造出的查询不可能命中任何切片")
    void blankQueryIsMatchNone() {
        assertTrue(EsChunkUtil.keywordQuery("").isMatchNone());
        assertTrue(EsChunkUtil.keywordQuery("   ").isMatchNone());
        assertTrue(EsChunkUtil.keywordQuery(null).isMatchNone());
    }
}
