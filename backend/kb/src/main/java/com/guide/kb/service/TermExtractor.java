package com.guide.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.common.config.PromptProperties;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.dto.ExtractedTerm;
import com.guide.kb.enums.TermType;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 入库即抽取：切分完成后把切片按批送进模型，抽出部位 / 症状术语。
 *
 * <p><b>为什么放在 kb 而不是 llm</b>：{@code llm} 只留"唯一外部出口"的模型客户端；
 * 领域提示词与输出协议（JSON 数组、part / symptom）属于知识库这一层。
 *
 * <p><b>抽取是旁路</b>：任何失败都只记 WARN、**绝不上抛**——一次模型抖动不能让整份文档白解析
 * （DocumentMind 按次计费）。返回值里未抽中的切片照常带着空的 terms 交回去，入库不受影响。
 *
 * <p><b>词面校验不在这里重写</b>：单字必须挡在入口，而"哪些词算合法"只有一个实现
 * （{@link MedicalTermService#normalizeTerm}）。在下游再写一套，两套规则迟早对不上——
 * 而单字漏进白名单会让信息充足性门槛（子串匹配）当场失真。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TermExtractor {

    /** 一批多少片：与入库批次对齐（{@code DocIngestService.INDEX_BATCH_SIZE}） */
    static final int BATCH_SIZE = 20;

    /**
     * 连续失败多少批就熔断。
     *
     * <p><b>3 是经验阈值</b>：连续三次同一路径失败（鉴权、地址、配额）几乎可断定不是网络抖动，
     * 继续发下去只会白烧调用；而单批偶发失败不算数，成功一次即清零。
     */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final PromptProperties prompts;
    private final MedicalTermService medicalTermService;

    /**
     * 抽取整份文档的切片。
     *
     * @return 抽到的术语（按 (term, type) 跨批去重）与**带上本批术语**的切片列表
     */
    public ExtractionResult extract(String docId, List<ChunkInput> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return ExtractionResult.none(chunks);
        }
        List<ChunkInput> withTerms = new ArrayList<>(chunks.size());
        List<ExtractedTerm> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int index = 0;
        int batchNo = 0;
        int failedBatches = 0;
        int consecutiveFailures = 0;
        boolean broken = false;
        while (index < chunks.size()) {
            batchNo++;
            List<ChunkInput> batch = chunks.subList(index, Math.min(index + BATCH_SIZE, chunks.size()));
            index += batch.size();
            try {
                List<ExtractedTerm> batchTerms = parse(chatModel.chat(messagesOf(batch)));
                // 只在文档里真的出现过才算（不做同义词扩展）：模型的"归纳"不是知识库里的词
                batchTerms.removeIf(term -> !appearsIn(chunks, term.term()));
                consecutiveFailures = 0;
                for (ExtractedTerm term : batchTerms) {
                    if (seen.add(key(term.term(), term.type().getCode()))) {
                        merged.add(term);
                    }
                }
                withTerms.addAll(applyTerms(batch, batchTerms));
            } catch (RuntimeException e) {
                consecutiveFailures++;
                failedBatches++;
                log.warn("术语抽取单批失败（继续下一批）：docId={} 批={} 原因={}", docId, batchNo, e.getMessage());
                withTerms.addAll(batch);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("术语抽取熔断：docId={} 连续 {} 批失败，中止本文档剩余批次"
                                    + "（连续三次同一路径失败几乎可断定不是网络抖动，继续只会白烧调用）；最近原因={}",
                            docId, consecutiveFailures, e.getMessage());
                    broken = true;
                    break;
                }
            }
        }
        // 熔断后剩余的批次原样返回（不带术语），入库照常；正常走完时没有剩余，不重复追加
        if (broken && index < chunks.size()) {
            withTerms.addAll(chunks.subList(index, chunks.size()));
        }

        // 「已尝试」而不是「批次数」：熔断后 batchNo 是**尝试过**的批数（含失败那几批），
        // 叫「批次数」会被读成"成功抽取的批次"
        log.info("术语抽取完成：docId={} 已尝试 {} 批 失败批次={} 抽到词条 {}（跨批去重后）",
                docId, batchNo, failedBatches, merged.size());
        if (broken) {
            log.info("术语抽取已中止，剩余 {} 片未抽取：docId={}", chunks.size() - index, docId);
        }
        return new ExtractionResult(merged, withTerms);
    }

    private List<ChatMsg> messagesOf(List<ChunkInput> batch) {
        StringBuilder text = new StringBuilder();
        for (ChunkInput chunk : batch) {
            text.append("标题：").append(chunk.title() == null ? "" : chunk.title()).append('\n')
                    .append("正文：").append(chunk.content() == null ? "" : chunk.content()).append("\n\n");
        }
        String system = prompts.getTermExtract().getSystem();
        String user = PromptProperties.render(prompts.getTermExtract().getUserTemplate(),
                PromptProperties.PLACEHOLDER_TEXT, text.toString());
        return List.of(ChatMsg.system(system), ChatMsg.user(user));
    }

    /**
     * 解析模型输出：JSON 数组 {@code [{"term":"...","type":"part"|"symptom"}]}。
     *
     * <p>**非法 type 降级为 symptom、不丢词**——类型错了人能在管理端改，丢了真实的词更亏。
     * 词面不合法的（单字 / 超长 / 内部空白）在这里丢掉，判定用的是白名单的同一套规则。
     *
     * @throws IllegalArgumentException 输出不是可解析的 JSON 数组（算单批失败）
     */
    private List<ExtractedTerm> parse(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String cleaned = stripFence(output.strip());
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出不是 JSON 数组");
        }
        JsonNode array;
        try {
            array = OBJECT_MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("模型输出的 JSON 无法解析：" + e.getMessage(), e);
        }
        List<ExtractedTerm> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : array) {
            String text = medicalTermService.normalizeTerm(node.path("term").isMissingNode()
                    ? null : node.path("term").asText(null));
            if (text == null) {
                continue;
            }
            TermType type = "part".equalsIgnoreCase(node.path("type").asText(""))
                    ? TermType.PART : TermType.SYMPTOM;
            if (seen.add(key(text, type.getCode()))) {
                terms.add(new ExtractedTerm(text, type));
            }
        }
        return terms;
    }

    /** 把本批抽到的词挂到本批每一片切片上（ES 一次写入即带 terms，不走二次更新） */
    private List<ChunkInput> applyTerms(List<ChunkInput> batch, List<ExtractedTerm> terms) {
        List<String> words = terms.stream().map(ExtractedTerm::term).distinct().toList();
        List<ChunkInput> out = new ArrayList<>(batch.size());
        for (ChunkInput chunk : batch) {
            out.add(new ChunkInput(chunk.title(), chunk.content(), chunk.seq(), words, chunk.type()));
        }
        return out;
    }

    /** 词是否在文档（标题 + 正文）里真的出现过——模型偶尔会"归纳"出原文没有的词 */
    private boolean appearsIn(List<ChunkInput> chunks, String term) {
        for (ChunkInput chunk : chunks) {
            String title = chunk.title() == null ? "" : chunk.title();
            String content = chunk.content() == null ? "" : chunk.content();
            if (title.contains(term) || content.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** 模型习惯把 JSON 包进 ``` 围栏 */
    private String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, closing).strip();
    }

    private String key(String term, String typeCode) {
        return term + '\u0000' + typeCode;
    }

    /**
     * 抽取结果：抽到的词 + 带上了词的本批切片。
     *
     * <p>两者都返回是因为下游要的东西不同：词用于合并进白名单（整份文档一次），
     * 切片用于随同一次 ES 写入落 {@code medical_terms}（按批）。
     */
    public record ExtractionResult(List<ExtractedTerm> terms, List<ChunkInput> chunks) {

        public ExtractionResult {
            terms = terms == null ? List.of() : List.copyOf(terms);
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }

        /** 什么都没抽到：切片原样返回（失败与"模型没给词"都走它） */
        public static ExtractionResult none(List<ChunkInput> chunks) {
            return new ExtractionResult(List.of(), chunks);
        }
    }
}
