package com.guide.common.util;

import com.guide.common.model.LayoutBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 召回侧排除标题片的规则（{@link ChunkRetrievalFilter}）。
 *
 * <p>为什么值得钉一个测试：这条规则是 2026-10-03 从**实测**里捞出来的——
 * 活切片 144 片里 70 片是标题片（正文等于标题本身），其中 69 片被同文档正文片的 {@code title}
 * 列重复；26 次检索里 12 次 Top1 就是标题片，把正文片挤出 Top-K，模型于是把结论挂在空洞标题上。
 * 它看起来像"顺手加的一个过滤"，删掉不会编译失败、也不会让任何接口报错，
 * **只会让推荐卡的「判断依据」重新变成一句句标题**——所以用字面量钉住。
 *
 * <p>行为层面的证据在端到端：改完后同样的主诉，检索日志里的「精排 #N 正文」不再出现只含标题的片段。
 * common 模块没有 pgvector / ES 的测试设施，所以这里只钉规则本身（取值与两处方言同源）。
 */
class ChunkRetrievalFilterTest {

    @Test
    @DisplayName("排除项就是 chunk_type=title")
    void excludesTitleChunk() {
        assertEquals(LayoutBlock.BlockType.TITLE, ChunkRetrievalFilter.EXCLUDED_TYPE);
        assertEquals("title", ChunkRetrievalFilter.excludedCode());
    }

    @Test
    @DisplayName("pgvector 谓词写成字面量：改取值必须是有意识的决定")
    void sqlPredicateIsExactLiteral() {
        assertEquals("chunk_type <> 'title'", ChunkRetrievalFilter.sqlPredicate());
    }

    @Test
    @DisplayName("两路召回共用一个来源：ES 的 term 过滤与 SQL 谓词取同一个编码值")
    void bothDialectsShareOneSource() {
        assertEquals(ChunkRetrievalFilter.excludedCode(), ChunkRetrievalFilter.EXCLUDED_TYPE.getCode());
        assertEquals("title", ChunkRetrievalFilter.excludedCode());
    }
}
