package com.guide.common.util;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.guide.common.config.ElasticsearchProperties;
import com.guide.common.model.ChunkHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ES chunk 索引读写工具（管全文检索与术语聚合）。
 * 写入方：kb（唯一写入口，与 pgvector 双写）；读取方：rag（BM25 关键词召回）。
 * 中文分词用 analysis-ik（索引 ik_max_word / 检索 ik_smart）。
 * 可用性策略：ES 不可用时不抛异常打断主链路——召回退化为空列表（由 rag 侧融合兜底）。
 */
@Slf4j
@Component
public class EsChunkUtil {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public EsChunkUtil(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 建索引（不存在时），含中文分词 mapping；已存在时不改任何字段类型，只补缺失的 chunk_type。
     *
     * <p><b>重建路径（改 mapping 后必须走一次）</b>：ES 不允许原地改字段类型，
     * {@code putMapping} 对已存在的 {@code medical_terms: keyword} 会直接报错。
     * 2026-10-17 该字段从 {@code keyword} 改成 {@code text}（术语加分需要 ik 分词才能被 match 命中），
     * 旧索引因此需要**人工**重建：先删索引、重启应用让它按新 mapping 重建、再重灌切片。
     * 检测到旧 mapping 时这里只记 WARN，**不自动删索引**——删索引丢数据，必须是人的运维决定。
     * 具体步骤见 {@code docs/architecture/链路A-导诊对话.md} §6.5 的「索引重建」段。
     */
    public void ensureIndex() {
        String index = properties.getChunkIndex();
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (exists) {
                warnIfTermMappingStale(index);
                client.indices().putMapping(p -> p.index(index)
                        .properties("chunk_type", property -> property.keyword(k -> k)));
                log.info("ES 索引已补充 chunk_type mapping：{}", index);
                return;
            }
            client.indices().create(c -> c.index(index).mappings(chunkIndexMapping()));
            log.info("ES 索引已创建：{}", index);
        } catch (IOException | RuntimeException e) {
            log.warn("ES 索引创建失败（{}）：{}", index, e.getMessage());
        }
    }

    /**
     * chunk 索引的完整 mapping（建索引时使用；测试也读它钉住字段类型）。
     *
     * <p>{@code medical_terms} 是 {@code text}（索引 {@code ik_max_word} / 检索 {@code ik_smart}）
     * 而不是 {@code keyword}：{@code keyword} 在 {@code match} 查询里匹配不上中文术语，
     * 「关键词通道给术语加分」会静默失效。{@code .keyword} 子字段留给精确匹配与将来聚合。
     */
    static TypeMapping chunkIndexMapping() {
        return TypeMapping.of(m -> m
                .properties("chunk_id", p -> p.keyword(k -> k))
                .properties("dept_id", p -> p.keyword(k -> k))
                .properties("title", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                .properties("content", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")))
                .properties("chunk_type", p -> p.keyword(k -> k))
                .properties("medical_terms", p -> p.text(t -> t.analyzer("ik_max_word").searchAnalyzer("ik_smart")
                        .fields("keyword", f -> f.keyword(k -> k)))));
    }

    /**
     * 旧索引的 {@code medical_terms} 是否还是 {@code keyword}（需要人工重建）。
     *
     * <p>字段缺失返回 {@code false}：那是更老的索引，{@code putMapping} 能补新字段，不需要重建。
     */
    static boolean termMappingNeedsRebuild(TypeMapping mapping) {
        if (mapping == null) {
            return false;
        }
        Property terms = mapping.properties().get("medical_terms");
        return terms != null && !terms.isText();
    }

    private void warnIfTermMappingStale(String index) throws IOException {
        var response = client.indices().getMapping(g -> g.index(index));
        var record = response.result().get(index);
        TypeMapping mapping = record == null ? null : record.mappings();
        if (termMappingNeedsRebuild(mapping)) {
            log.warn("ES 索引 medical_terms 仍是 keyword，术语加分不会生效——需人工重建："
                    + "删索引 {} 后重启应用重建，再重灌切片（ES 不允许原地改字段类型）", index);
        }
    }

    /** 批量写入/覆盖 chunk（与 pgvector 同事务边界内的第二路） */
    public void indexChunks(List<ChunkDoc> docs) {
        if (docs.isEmpty()) {
            return;
        }
        String index = properties.getChunkIndex();
        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (ChunkDoc doc : docs) {
                builder.operations(op -> op.index(idx -> idx.index(index).id(doc.chunkId()).document(doc)));
            }
            var response = client.bulk(builder.build());
            if (response.errors()) {
                log.warn("ES 批量写入部分失败，index={}", index);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("ES 写入失败：" + e.getMessage(), e);
        }
    }

    /** 删除 chunk（删除补偿顺序：先删 ES → 再删向量 → 再删元数据） */
    public void deleteChunk(String chunkId) {
        try {
            client.delete(d -> d.index(properties.getChunkIndex()).id(chunkId));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("ES 删除失败：" + e.getMessage(), e);
        }
    }

    /**
     * 批量删除 chunk（删文档 / 重新入库「先删旧切片」走这条）。
     *
     * <p>用 bulk 而不是逐条 delete：一份文档的切片数在几十到几百，重新入库时逐条发
     * HTTP 会把「先删旧再重建」的删除段拖成分钟级。
     *
     * <p>失败分两种：**传输层失败（连不上 / 超时 / 非 2xx）直接抛**——删除补偿的顺序保证
     * （先删 ES → 再删向量 → 再删元数据）不能靠「删不掉就算了」推进，抛出去让调用方停在这一步；
     * **bulk 内部的逐条错误只记 WARN**（与 {@link #indexChunks} 同形）——残留在 ES 的切片
     * 同样是"命中在库、元数据已删"，读时由回填按 MySQL 存在性丢弃，不会进 Prompt。
     */
    public void deleteChunks(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String index = properties.getChunkIndex();
        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (String chunkId : chunkIds) {
                builder.operations(op -> op.delete(d -> d.index(index).id(chunkId)));
            }
            var response = client.bulk(builder.build());
            if (response.errors()) {
                log.warn("ES 批量删除部分失败，index={}", index);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("ES 批量删除失败：" + e.getMessage(), e);
        }
    }

    /**
     * 术语加分权重（{@code medical_terms} 的 should 子句）。
     *
     * <p><b>为什么是 1.0、且写死不进 {@code sys_config}</b>：{@code medical_terms} 是短字段，
     * BM25 的字段长度归一化（{@code b=0.75}）会让它命中时的分天然偏高；拍 2.0 会让
     * 「只列了术语、正文无关」的切片冲进 Top-K。1.0 是起点，靠真实语料验证后再调；
     * 为一个尚未验证的数新增受管参数，要连带白名单、管理端页面、文档四处，不划算。
     */
    static final float MEDICAL_TERMS_BOOST = 1.0f;

    /**
     * 关键词通道的查询形状（包内可见的纯函数，单测钉住召回范围）：
     * <pre>
     * bool:
     *   must:    multi_match(query, fields: ["title^2", "content"])
     *   should:  match(medical_terms, query), boost = 1.0
     *   mustNot: chunk_type = "title"      // 既有 ChunkRetrievalFilter，原样保留
     * </pre>
     *
     * <p><b>术语走 should 而不是塞进 multiMatch</b>：{@code best_fields} 是"取最高分字段"，
     * 术语多的切片会把标题信号顶掉；{@code should} 才是加法，且默认
     * {@code minimum_should_match = 0}——**不改变原有召回集**（不命中不影响进 Top-K，命中才加分）。
     *
     * <p>空查询返回 {@code match_none}：不构造一个空词项的 bool（那样会命中全库或报错），
     * 调用方拿到的是"这一路没有候选"。
     */
    static Query keywordQuery(String query) {
        if (query == null || query.isBlank()) {
            return Query.of(qb -> qb.matchNone(mn -> mn));
        }
        return Query.of(qb -> qb.bool(b -> b
                .must(m -> m.multiMatch(mm -> mm.query(query).fields("title^2", "content")))
                .should(s -> s.match(mt -> mt.field("medical_terms").query(query)
                        .boost(MEDICAL_TERMS_BOOST)))
                // 标题片不进召回（ChunkRetrievalFilter）：正文等于标题本身、语义已由正文片的
                // title 列携带，且会在 BM25 上挤占 Top-K（2026-10-03 实测 46% 的 Top1 是标题片）
                .mustNot(mn -> mn.term(t -> t.field("chunk_type")
                        .value(ChunkRetrievalFilter.excludedCode())))));
    }

    /**
     * BM25 关键词召回。ES 不可用/索引不存在时返回空列表（rag 侧退化为单路召回）。
     */
    public List<ChunkHit> searchChunks(String query, int topK) {
        try {
            var response = client.search(s -> s
                            .index(properties.getChunkIndex())
                            .size(topK)
                            .query(keywordQuery(query)),
                    ChunkDoc.class);
            List<ChunkHit> hits = new ArrayList<>();
            for (Hit<ChunkDoc> hit : response.hits().hits()) {
                ChunkDoc doc = hit.source();
                if (doc == null) {
                    continue;
                }
                hits.add(new ChunkHit(doc.chunkId(), doc.deptId(), doc.title(), doc.content(),
                        hit.score() == null ? 0d : hit.score()));
            }
            return hits;
        } catch (IOException | RuntimeException e) {
            log.warn("ES 召回失败，本次退化为单路召回：{}", e.getMessage());
            return List.of();
        }
    }

    /** ES chunk 文档（字段名与索引 mapping 对齐） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkDoc(
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("dept_id") String deptId,
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("chunk_type") String chunkType,
            @JsonProperty("medical_terms") List<String> medicalTerms) {
    }
}
