package com.guide.rag;

import com.guide.common.config.PromptProperties;
import com.guide.common.exception.BizException;
import com.guide.common.model.ChunkHit;
import com.guide.common.util.EsChunkUtil;
import com.guide.common.util.PgVectorUtil;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.llm.client.EmbeddingModel;
import com.guide.llm.client.RerankModel;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import com.guide.rag.dto.RagTurn;
import com.guide.rag.spi.ChunkTextProvider;
import com.guide.rag.support.PromptBuilder;
import com.guide.rag.support.RrfFuser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * RAG 检索层（技术核心）：查询改写 → 双路召回 → RRF 融合 → 精排 → Prompt 拼装。
 * 不感知业务状态（会话、用户）——科室范围、轮次约束与健康档案两段文本均由入参给定。
 * 降级策略：ES 不可用退化为向量单路；精排失败退化为 RRF 顺序；改写失败退回原查询。
 * 唯一不可降级的是 embedding——没有查询向量就没有召回，直接抛出由上层转 SSE error。
 *
 * <p>档案召回（单据 03）：{@code profileQuery} 非空时额外开两路召回（向量 + 关键词）一起进 RRF，
 * 只扩容候选池、不参与精排排序；为空则与今天的两路召回完全一致。
 */
@Slf4j
@Service
public class RagService {

    /** 最近历史参与改写的条数上限（控制 token 与延迟） */
    private static final int REWRITE_HISTORY_LIMIT = 6;
    private static final int REWRITE_MAX_CHARS = 80;

    private final EmbeddingModel embeddingModel;
    private final RerankModel rerankModel;
    private final ChatModel chatModel;
    private final PgVectorUtil pgVectorUtil;
    private final EsChunkUtil esChunkUtil;
    private final ChunkTextProvider chunkTextProvider;
    private final PromptBuilder promptBuilder;
    private final PromptProperties prompts;

    public RagService(EmbeddingModel embeddingModel, RerankModel rerankModel, ChatModel chatModel,
                      PgVectorUtil pgVectorUtil, EsChunkUtil esChunkUtil,
                      ChunkTextProvider chunkTextProvider, PromptBuilder promptBuilder,
                      PromptProperties prompts) {
        this.embeddingModel = embeddingModel;
        this.rerankModel = rerankModel;
        this.chatModel = chatModel;
        this.pgVectorUtil = pgVectorUtil;
        this.esChunkUtil = esChunkUtil;
        this.chunkTextProvider = chunkTextProvider;
        this.promptBuilder = promptBuilder;
        this.prompts = prompts;
    }

    /**
     * 检索：产出送入 Prompt 的精排片段与证据素材。
     * 各阶段打 INFO 日志（召回条数与最高分、融合候选、精排结果与耗时），
     * 逐条明细走 DEBUG——检索质量要靠这些数据才观测得到。
     */
    public RagContext retrieve(RagRequest request) {
        long start = System.currentTimeMillis();
        log.info("检索开始：多轮={} topK={} topN={}｜输入={}", request.multiTurn(), request.topK(), request.topN(),
                abbreviate(request.query(), 60));

        String query = rewriteQuery(request);
        if (!query.equals(request.query())) {
            log.info("查询改写：{} → {}", abbreviate(request.query(), 40), abbreviate(query, 40));
        }

        long embedStart = System.currentTimeMillis();
        float[] queryVector = embeddingModel.embed(query);
        long embedMs = System.currentTimeMillis() - embedStart;

        long recallStart = System.currentTimeMillis();
        List<ChunkHit> vectorHits = pgVectorUtil.searchChunks(queryVector, request.topK());
        List<ChunkHit> esHits = esChunkUtil.searchChunks(query, request.topK());
        long recallMs = System.currentTimeMillis() - recallStart;
        log.info("双路召回：向量 {} 条{}｜关键词 {} 条{}｜embedding {} ms 召回 {} ms",
                vectorHits.size(), topScore(vectorHits), esHits.size(), topScore(esHits), embedMs, recallMs);
        log.debug("向量路明细：{}", hitSummary(vectorHits));
        log.debug("关键词路明细：{}", hitSummary(esHits));

        // 排名列表：主诉双路 + 档案双路（检索用串非空时才追加）；RrfFuser 本就吃「一个排名列表的列表」
        List<List<ChunkHit>> rankedLists = new ArrayList<>(4);
        rankedLists.add(vectorHits);
        rankedLists.add(esHits);
        appendProfileRecall(request, rankedLists);

        List<ChunkHit> fused = fillTexts(RrfFuser.fuse(rankedLists, RrfFuser.DEFAULT_K));
        if (fused.isEmpty()) {
            log.info("检索结束：无命中（检查知识库是否有切片 / 索引是否建好）｜总耗时 {} ms",
                    System.currentTimeMillis() - start);
            return new RagContext(request.query(), query, List.of(), vectorHits.size(), esHits.size());
        }
        log.info("RRF 融合：{} 个排名列表 → 候选 {} 条（主诉双路同时命中 {} 条）｜Top3 {}",
                rankedLists.size(), fused.size(), bothPathCount(vectorHits, esHits), rrfSummary(fused, 3));

        long rerankStart = System.currentTimeMillis();
        // 精排 query 仍是主诉改写串——这是「档案不能决定推荐科室」在检索层的结构性保证：
        // 档案捞回的切片必须靠**主诉相关性**才能挤进前 N，一篇「与主诉无关、只是沾了档案」的切片进不来
        List<ChunkHit> chunks = rerank(query, fused, request.topN());
        long rerankMs = System.currentTimeMillis() - rerankStart;

        log.info("精排完成：{} 条候选 → Top{}｜精排 {} ms｜检索总耗时 {} ms",
                fused.size(), chunks.size(), rerankMs, System.currentTimeMillis() - start);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkHit hit = chunks.get(i);
            log.info("  精排 #{} score={} chunkId={} 《{}》", i + 1, format(hit.score()), hit.chunkId(), hit.title());
            log.debug("  精排 #{} 正文：{}", i + 1, abbreviate(hit.content(), 200));
        }
        return new RagContext(request.query(), query, chunks, vectorHits.size(), esHits.size());
    }

    /** 召回最高分：向量路是余弦相似度，关键词路是 BM25 分（量纲不同，只作观测） */
    private String topScore(List<ChunkHit> hits) {
        return hits.isEmpty() ? "（空）" : "（最高 " + format(hits.get(0).score()) + "）";
    }

    private String hitSummary(List<ChunkHit> hits) {
        return hits.stream()
                .map(hit -> format(hit.score()) + "#" + hit.chunkId())
                .collect(Collectors.joining(" "));
    }

    private String rrfSummary(List<ChunkHit> fused, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, fused.size()); i++) {
            ChunkHit hit = fused.get(i);
            sb.append('[').append(i + 1).append(']').append(format(hit.score()))
                    .append('#').append(abbreviate(hit.title(), 24)).append(' ');
        }
        return sb.toString().trim();
    }

    private long bothPathCount(List<ChunkHit> vectorHits, List<ChunkHit> esHits) {
        Set<String> esIds = esHits.stream().map(ChunkHit::chunkId).collect(Collectors.toSet());
        return vectorHits.stream().filter(hit -> esIds.contains(hit.chunkId())).count();
    }

    /**
     * 档案召回（单据 03）：检索用串非空 ⇒ 多一次 embedding + 两路召回，为本轮 RRF 追加两个排名列表。
     *
     * <p><b>只扩容、不偏置</b>：本方法只往候选池里加片，不改精排 query（仍是主诉改写串）。
     * 档案捞回的切片必须靠主诉相关性才能进前 N——「不决定性」因此没有变成一堆 if，
     * 而是变成两个「没做」：没给排序权、没给注号位。
     *
     * <p><b>为空即零成本回归</b>：检索用串为空时一个列表都不加、一次 embedding 都不多调，
     * 恰好与今天的两路召回完全一致。日志只记条数，不落档案原文（档案是患者健康信息，不经日志外泄）。
     */
    private void appendProfileRecall(RagRequest request, List<List<ChunkHit>> rankedLists) {
        String profileQuery = request.profileQuery();
        if (!StringUtils.hasText(profileQuery)) {
            return;
        }
        long embedStart = System.currentTimeMillis();
        float[] profileVector = embeddingModel.embed(profileQuery);
        long embedMs = System.currentTimeMillis() - embedStart;

        long recallStart = System.currentTimeMillis();
        List<ChunkHit> profileVectorHits = pgVectorUtil.searchChunks(profileVector, request.topK());
        List<ChunkHit> profileEsHits = esChunkUtil.searchChunks(profileQuery, request.topK());
        long recallMs = System.currentTimeMillis() - recallStart;
        log.info("档案召回：向量 {} 条{}｜关键词 {} 条{}｜embedding {} ms 召回 {} ms",
                profileVectorHits.size(), topScore(profileVectorHits),
                profileEsHits.size(), topScore(profileEsHits), embedMs, recallMs);
        log.debug("档案向量路明细：{}", hitSummary(profileVectorHits));
        log.debug("档案关键词路明细：{}", hitSummary(profileEsHits));

        rankedLists.add(profileVectorHits);
        rankedLists.add(profileEsHits);
    }

    private String format(double score) {
        return String.format("%.4f", score);
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /**
     * 回填切片正文（MySQL 事实源）。向量路只回 id，正文必须现取；
     * 顺带丢弃「向量在库、元数据已删」的脏命中，保证 Prompt 里的片段都可溯源。
     */
    private List<ChunkHit> fillTexts(List<ChunkHit> fused) {
        if (fused.isEmpty()) {
            return fused;
        }
        Set<String> chunkIds = fused.stream().map(ChunkHit::chunkId).collect(Collectors.toSet());
        Map<String, ChunkTextProvider.ChunkText> texts = chunkTextProvider.loadTexts(chunkIds);
        List<ChunkHit> filled = new ArrayList<>(fused.size());
        for (ChunkHit hit : fused) {
            ChunkTextProvider.ChunkText text = texts.get(hit.chunkId());
            if (text != null) {
                filled.add(new ChunkHit(hit.chunkId(), hit.deptId(), text.title(), text.content(), hit.score()));
            } else {
                log.warn("命中片段在 MySQL 已不存在，已丢弃：chunkId={}", hit.chunkId());
            }
        }
        return filled;
    }

    /**
     * 流式生成：拼 Prompt（知识片段 + 历史 + 本轮输入）后调模型逐段回调。
     * 这是 chat 侧唯一的生成入口——chat 不直连 LLM，只经 rag → llm（见 CLAUDE.md 依赖规范）。
     *
     * @return 拼接后的完整输出（含结论 JSON 部分，由 {@link AnswerParser} 解析）
     */
    public String streamAnswer(RagRequest request, RagContext context, Consumer<String> onDelta) {
        List<ChatMsg> messages = promptBuilder.build(request, context);
        if (log.isDebugEnabled()) {
            log.debug("Prompt 组装：system {} 字｜历史 {} 条｜知识片段 {} 条｜本轮输入 {} 字",
                    messages.get(0).content().length(), request.history().size(), context.chunks().size(),
                    request.query().length());
        }
        return chatModel.chatStream(messages, onDelta);
    }

    /**
     * 查询改写：多轮对话时把「它/还有/那个」等指代还原为独立可检索的查询。
     * 失败（模型不可用）不阻断主流程，退回患者原话。
     */
    private String rewriteQuery(RagRequest request) {
        if (!request.multiTurn()) {
            return request.query();
        }
        try {
            StringBuilder dialogue = new StringBuilder();
            List<RagTurn> history = request.history();
            int from = Math.max(0, history.size() - REWRITE_HISTORY_LIMIT);
            for (RagTurn msg : history.subList(from, history.size())) {
                dialogue.append("assistant".equals(msg.role()) ? "助手：" : "患者：")
                        .append(msg.content()).append('\n');
            }
            dialogue.append("患者：").append(request.query());

            // 提示词来自 prompts.yml（rewrite.system / rewrite.user-template）
            String rewritten = chatModel.chat(List.of(
                    ChatMsg.system(prompts.getRewrite().getSystem()),
                    ChatMsg.user(PromptProperties.render(prompts.getRewrite().getUserTemplate(),
                            PromptProperties.PLACEHOLDER_DIALOGUE, dialogue.toString()))));
            String cleaned = cleanup(rewritten);
            if (!StringUtils.hasText(cleaned)) {
                return request.query();
            }
            log.debug("查询改写：{} → {}", request.query(), cleaned);
            return cleaned;
        } catch (BizException e) {
            log.warn("查询改写失败，使用患者原话检索：{}", e.getMessage());
            return request.query();
        }
    }

    /** 清理改写结果：去引号/换行/前缀说明，超长截断 */
    private String cleanup(String rewritten) {
        if (!StringUtils.hasText(rewritten)) {
            return "";
        }
        String cleaned = rewritten.trim()
                .replaceAll("^[\"'「『]|[\"'」』]$", "")
                .replaceAll("^(改写后|改写结果|查询)[:：]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > REWRITE_MAX_CHARS ? cleaned.substring(0, REWRITE_MAX_CHARS) : cleaned;
    }

    /** 精排 Top-N；失败退化为 RRF 顺序，保证导诊不中断 */
    private List<ChunkHit> rerank(String query, List<ChunkHit> fused, int topN) {
        List<String> documents = fused.stream()
                .map(hit -> (hit.title() == null ? "" : hit.title() + "。") + hit.content())
                .toList();
        try {
            List<RerankModel.RerankHit> hits = rerankModel.rerank(query, documents, topN);
            List<ChunkHit> ranked = hits.stream()
                    .filter(hit -> hit.index() >= 0 && hit.index() < fused.size())
                    .map(hit -> withScore(fused.get(hit.index()), hit.score()))
                    .toList();
            return ranked.isEmpty() ? fused.stream().limit(topN).toList() : ranked;
        } catch (BizException e) {
            log.warn("精排失败，退化为 RRF 融合顺序——注意此后片段分数是 RRF 分（量级 ~0.01，"
                    + "不是 0–1 相关度），Prompt 里的「相关度」与证据快照的 score 含义随之变化：{}", e.getMessage());
            return fused.stream().limit(topN).toList();
        }
    }

    private ChunkHit withScore(ChunkHit hit, double score) {
        return new ChunkHit(hit.chunkId(), hit.deptId(), hit.title(), hit.content(), score);
    }
}
