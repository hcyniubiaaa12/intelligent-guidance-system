package com.guide.common.util;

import com.guide.common.model.LayoutBlock;

/**
 * 召回侧的块类型闸：**标题片（{@code chunk_type=title}）不进召回**。
 * pgvector 与 ES 两路共用这一条规则（各按自己的方言表达，但取值只有这一个来源）。
 *
 * <p><b>为什么标题片不该参与召回</b>（2026-10-03 实测）：
 * <ol>
 *   <li><b>它没有正文</b>：结构切刻意让每个标题单独成片，切出来的片 {@code content} 就等于标题本身
 *       （如"一、测试背景"）。召回它是零信息——模型拿到的"注N"只有一句标题，没有可引用的内容。</li>
 *   <li><b>它是重复存储</b>：正文片的 {@code title} 列本来就带着它归属的小节标题，所以同一个标题
 *       在库里存了两遍。实测活切片 144 片里 70 片是标题片，其中 <b>69 片的正文恰好等于同文档某个
 *       正文片的 {@code title}</b> ⇒ <b>排除它对召回语义零损失</b>。</li>
 *   <li><b>它挤占名额、并制造"引用与结论错位"</b>：标题片在向量（极短文本 vs 短主诉）与 BM25
 *       （症状词在标题里密度高）上都天然占便宜。实测 26 次检索里 <b>12 次（46%）Top1 就是标题片</b>，
 *       把真正有内容的正文片挤出 Top-K；模型没有正文可依，却又被要求"结论必须由注号支撑"，
 *       于是把结论挂在这些空洞标题上——推荐卡「判断依据」列出的是一条没有任何医学内容的依据。</li>
 * </ol>
 *
 * <p><b>标题片的用途仍然保留</b>：{@code chunk_type} 三处照旧落库（MySQL / ES / pgvector），
 * 管理端仍可据此回答"标题有没有被认出来"（见 {@code DocSplitter}）。本类只关掉它的**召回**，
 * 不动它的**写入**——所以这是一条可以随时撤回的读侧过滤，不需要重建索引。
 *
 * <p>写入侧不写（那需要重建索引、并同步链路 B 的 {@code chunk_broken} 重建路径）留待后续，
 * 届时本类可以整块删掉。
 */
public final class ChunkRetrievalFilter {

    private ChunkRetrievalFilter() {
    }

    /** 不进召回的块类型 */
    public static final LayoutBlock.BlockType EXCLUDED_TYPE = LayoutBlock.BlockType.TITLE;

    /** 被排除块的编码值（ES 的 term 过滤与 SQL 谓词共用此来源） */
    public static String excludedCode() {
        return EXCLUDED_TYPE.getCode();
    }

    /** pgvector 侧谓词（拼进 WHERE） */
    public static String sqlPredicate() {
        return "chunk_type <> '" + excludedCode() + "'";
    }
}
