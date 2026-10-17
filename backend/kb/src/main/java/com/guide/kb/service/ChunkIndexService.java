package com.guide.kb.service;

import com.guide.common.util.EsChunkUtil;
import com.guide.common.util.PgVectorUtil;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.entity.KbChunk;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.kb.mapper.KbChunkMapper;
import com.guide.llm.client.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * chunk 入库服务：kb 是**唯一**写向量库与 ES chunk 索引的入口（上传流水线 + 回流同步 + 种子语料）。
 *
 * <p>写入边界：MySQL 事实 + pgvector 向量（含正文副本）+ ES 全文，**同一方法内顺序写入，
 * 不是同一事务**——MySQL 在事务里，pgvector 走独立连接、ES 走 HTTP，两者都吃不到事务。
 * 因此失败分两条路收敛：MySQL 由 {@code @Transactional} 自己回滚，已写入的向量由
 * {@link #compensateWrittenVectors} 主动回删（见 `docs/architecture/链路B-知识库入库.md` 对齐点·写时补偿）；
 * 删文档时的补偿顺序则是 先删 ES → 再删向量 → 再删元数据（见 {@link #deleteChunk}）。
 *
 * <p>写进 pgvector 的 title / content 是 MySQL 切片的**副本，只作人工排查用**——检索不读它们。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkIndexService {

    private final KbChunkMapper chunkMapper;
    private final EmbeddingModel embeddingModel;
    private final PgVectorUtil pgVectorUtil;
    private final EsChunkUtil esChunkUtil;

    /**
     * 回流合成 chunk：一段已定稿的鉴别诊断文本，不走解析/切分。
     *
     * <p>文本是管理员预览定稿的，再切一次只会把一条完整的鉴别关系拦腰切断
     * （那正是根因表里的 {@code chunk_broken}）。序号接在容器现有切片之后，
     * 修正重审替换时先删旧片，新片仍接在末尾。
     *
     * <p><b>刻意不抽术语</b>：只有一片、文本由管理员定稿，术语在预览时由人决定
     * （不进自动抽取，也不进白名单候选）。这不是漏接——回流的知识形态是"人写的鉴别语料"，
     * 与上传文档"模型从原文抽词"是两条不同的来源，混在一起会让候选池混入人工稿的用词。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbChunk indexSyntheticChunk(String docId, String deptId, String title, String content) {
        Integer maxSeq = chunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                        .eq(KbChunk::getDocId, docId)
                        .orderByDesc(KbChunk::getSeq)
                        .last("LIMIT 1"))
                .stream().map(KbChunk::getSeq).findFirst().orElse(0);
        List<KbChunk> saved = indexChunks(docId, deptId, List.of(
                new ChunkInput(title, content, maxSeq + 1, List.of())));
        return saved.get(0);
    }

    /**
     * 批量入库：向量化一次批调（摊薄成本），再逐条写 MySQL + pgvector + ES。
     *
     * @param docId  所属文档 id（回流合成 chunk 指向系统级「回流知识文档」容器）
     * @param deptId 所属科室 id（向量召回过滤与推荐校验用）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<KbChunk> indexChunks(String docId, String deptId, List<ChunkInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<String> texts = inputs.stream().map(this::embeddingText).toList();
        List<float[]> vectors = embeddingModel.embedBatch(texts);
        if (vectors.size() != inputs.size()) {
            throw new IllegalStateException("向量条数与切片数不一致：" + vectors.size() + "/" + inputs.size());
        }

        List<KbChunk> saved = new ArrayList<>(inputs.size());
        List<EsChunkUtil.ChunkDoc> docs = new ArrayList<>(inputs.size());
        List<String> writtenVectorIds = new ArrayList<>(inputs.size());
        try {
            for (int i = 0; i < inputs.size(); i++) {
                ChunkInput input = inputs.get(i);
                KbChunk chunk = new KbChunk();
                chunk.setDocId(docId);
                chunk.setTitle(input.title());
                chunk.setContent(input.content());
                chunk.setSeq(input.seq());
                chunk.setChunkType(input.type());
                chunkMapper.insert(chunk);

                pgVectorUtil.upsertChunkVector(new PgVectorUtil.ChunkVector(
                        chunk.getId(), deptId, docId, input.title(), input.content(), input.type(), vectors.get(i)));
                writtenVectorIds.add(chunk.getId());

                docs.add(new EsChunkUtil.ChunkDoc(chunk.getId(), deptId, input.title(), input.content(),
                        input.type().getCode(), termsForEs(input.terms())));
                saved.add(chunk);
            }
            esChunkUtil.indexChunks(docs);
        } catch (RuntimeException e) {
            compensateWrittenVectors(writtenVectorIds);
            throw e;
        }
        log.info("chunk 入库完成：docId={} deptId={} 条数={}", docId, deptId, saved.size());
        return saved;
    }

    /**
     * 写时补偿：**MySQL 在事务里，pgvector 与 ES 不在**——任一路写失败时 MySQL 会回滚，
     * 但已写入的向量不会跟着消失，必须用本次内存里记下的 chunk_id 主动回删。
     *
     * <p>只补向量、不补 ES：ES 残留同样是「命中在库、元数据已删」，由 rag 回填时按 MySQL
     * 存在性丢弃（不进 Prompt）；而这里的失败原因往往正是 ES 不可用，再去调它多半也是白费。
     * 补偿自身失败不能盖住原始异常，只留 ERROR 与 chunkId 供人工清理。
     */
    private void compensateWrittenVectors(List<String> writtenVectorIds) {
        if (writtenVectorIds.isEmpty()) {
            return;
        }
        try {
            int removed = pgVectorUtil.deleteChunkVectors(writtenVectorIds);
            log.warn("chunk 入库失败，已回删本次写入的向量：{} 条", removed);
        } catch (RuntimeException e) {
            log.error("写时补偿失败，向量残留待人工清理：chunkIds={}", writtenVectorIds, e);
        }
    }

    /** 删除补偿：先删 ES → 再删向量 → 再删元数据（顺序保证一致性收敛） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChunk(String chunkId) {
        esChunkUtil.deleteChunk(chunkId);
        pgVectorUtil.deleteChunkVector(chunkId);
        chunkMapper.deleteById(chunkId);
    }

    /**
     * ES 写入前的单字过滤：**只此一处实现**，种子语料与上传文档自动一致。
     *
     * <p>为什么不改 {@code SeedCorpus}：语料里的词是给 ES 用的（它们是切片正文里真实出现的词），
     * 过滤是写入侧的职责——把规则放在写入侧，任何来源都走同一条路，不会有人漏改。
     *
     * <p>单字为什么必须挡：{@code medical_terms} 参与关键词打分后，一个「胸」会命中半库切片，
     * 把真正相关的正文片挤出 Top-K。这与白名单入口的 2 字下限是同一个数
     * （{@link MedicalTermService#MIN_TERM_LENGTH}）——那里管入口判定，这里管检索打分。
     */
    private List<String> termsForEs(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream()
                .filter(term -> term != null && !term.isBlank()
                        && term.strip().codePointCount(0, term.strip().length())
                        >= MedicalTermService.MIN_TERM_LENGTH)
                .map(String::strip)
                .distinct()
                .toList();
    }

    /**
     * 向量化文本：标题 + 正文（标题也在语义里，提升召回质量）。
     *
     * <p>**标题片不重复拼**（2026-09-30）：标题片的 content 就是标题本身，再拼一遍会得到
     * 「标题。标题」，白占 token 还稀释了语义。
     */
    private String embeddingText(ChunkInput input) {
        String title = input.title() == null ? "" : input.title();
        String content = input.content() == null ? "" : input.content();
        if (title.isEmpty() || content.startsWith(title)) {
            return content;
        }
        return title + "。" + content;
    }
}
