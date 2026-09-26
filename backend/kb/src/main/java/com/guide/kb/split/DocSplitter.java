package com.guide.kb.split;

import com.guide.common.model.LayoutBlock;
import com.guide.kb.dto.ChunkInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切分：把版面块变成 chunk。三层**自上而下**，每层的触发条件互不重叠——
 * 「结构切（有标题）／模型切（无标题且够长）／递归切（超长，无条件）」。
 *
 * <p>三层不是"三个并列的策略"。并列写法有个覆盖漏洞：三个条件**都不满足**时无策略可走
 * （800 字无标题纯文本就是反例）。所以递归切是**无条件兜底**——任何 > {@code maxLength}
 * 的块，不管是结构切还是模型切产出的，最后都要过它一遍。
 *
 * <p>顺序上有一处与文档的字面顺序不同，值得写清楚：文档把三层列成
 * 「结构切 → 递归切 → 模型切」，而实现是「结构切 → （无标题部分）模型切 → 递归切兜底」。
 * 因为递归切若先无条件把长文本切碎，模型切面对的就已经是碎块，"给边界"这件事无从谈起——
 * 模型切正是用来**替代**结构切给不出边界的那个角色，它必须在机械切分之前出场。
 * 硬约束（递归切无条件兜底、模型切只在无标题处、模型切只给边界）三条都仍然成立。
 *
 * <p>表格块**作为一整块入 chunk，不参与正文切分**：它是解析层给出的完整语义单元，
 * 一行「症状｜科室｜鉴别方向」本身就是一条可被患者主诉命中的知识。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocSplitter {

    /** kb_chunk.title 是 VARCHAR(255)，超长会被 MySQL 截断或直接报错——在这里截干净 */
    private static final int TITLE_MAX = 255;

    /**
     * 递归切的分隔符优先级：段落 → 行 → 句 → 分句 → 词 → 硬切。
     * 用 lookbehind 切分，让分隔符**留在前一段尾部**——切完再拼回去要和原文一字不差。
     */
    private static final List<String> SEPARATORS = List.of("\n\n", "\n", "。", "；", "！", "？", "，", "、", " ");

    private final ModelSplitter modelSplitter;

    /**
     * @param blocks   版面块（已按阅读顺序排好；DocumentMind 的 index 是服务端排的，不需要自己排）
     * @param params   切分参数
     * @param docTitle 文档标题——**仅作无标题文本的 title 回落**，见 {@link #resolveTitle}
     * @return 切片（seq 从 1 连续编号）
     */
    public List<ChunkInput> split(List<LayoutBlock> blocks, SplitParams params, String docTitle) {
        List<ChunkInput> chunks = new ArrayList<>();
        for (Piece piece : structureSplit(blocks)) {
            if (piece.table()) {
                chunks.add(toChunk(piece.title(), piece.text(), docTitle));
                continue;
            }
            for (String text : splitText(piece.title(), piece.text(), params)) {
                chunks.add(toChunk(piece.title(), text, docTitle));
            }
        }
        return renumber(chunks);
    }

    // ---------- 第一层：结构切 ----------

    /**
     * 结构切：`type=title` 的块 = 切分边界，`title` 取最近的标题块。**不建层级树**——
     * 切分只需要边界、不需要层级：字号确实推得出层级（实测 H1=15 / H2=14 / 正文=12），
     * 但那是"能不能"不是"要不要"，而猜层级猜错只会把内容切得更碎。
     *
     * <p>标题块的文字**不进正文**：它进 title，而向量化文本是「标题 + 正文」（见
     * {@code ChunkIndexService.embeddingText}），信息一点没少，正文里再留一份只是重复。
     */
    private List<Piece> structureSplit(List<LayoutBlock> blocks) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String currentTitle = null;
        for (LayoutBlock block : blocks) {
            if (block == null) {
                continue;
            }
            switch (block.type()) {
                case TITLE -> {
                    flush(pieces, currentTitle, buffer);
                    currentTitle = block.text().strip();
                }
                case TABLE -> {
                    flush(pieces, currentTitle, buffer);
                    String markdown = block.markdown() == null || block.markdown().isBlank()
                            ? block.text().strip() : block.markdown().strip();
                    if (!markdown.isEmpty()) {
                        pieces.add(new Piece(currentTitle, markdown, true));
                    }
                }
                case TEXT -> {
                    if (!block.text().isBlank()) {
                        buffer.append(block.text().strip()).append('\n');
                    }
                }
            }
        }
        flush(pieces, currentTitle, buffer);
        return pieces;
    }

    private void flush(List<Piece> pieces, String title, StringBuilder buffer) {
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isEmpty()) {
            pieces.add(new Piece(title, text, false));
        }
    }

    // ---------- 第二层：模型切（只在无标题处）----------

    /**
     * 无标题的连续正文达到 {@code modelMinLength} 才值得花模型调用——**触发条件是
     * 「无标题但文本干净」，不是「质量低」**：质量低意味着解析出了问题，模型拿到的也是烂文本，
     * 拿最贵的手段去处理最该被拒绝的输入是反的。
     *
     * <p>模型切给出的是边界，所以结果仍要过**产出校验**（{@link #validateModelChunks}），
     * 且切出的块还要再走一次递归切（它可能给超长块）。
     */
    private List<String> splitText(String title, String text, SplitParams params) {
        if (title == null && text.length() >= params.modelMinLength()) {
            List<String> modelChunks = modelSplitter.split(text, params);
            validateModelChunks(text, modelChunks, params);
            List<String> out = new ArrayList<>();
            for (String chunk : modelChunks) {
                out.addAll(recursiveSplit(chunk, params));
            }
            return out;
        }
        return recursiveSplit(text, params);
    }

    /**
     * 产出校验：**不能寄望"模型切不出来就返回空"**——模型不会说自己切不出来，
     * 它会编一个看起来合理的切分。校验不过 → 抛（上层把文档标 failed，不是"done + 0 片"）。
     */
    private void validateModelChunks(String origin, List<String> chunks, SplitParams params) {
        if (chunks == null || chunks.isEmpty()) {
            throw new SplitValidationException("切分结果为空：模型未返回任何切片，请重新处理");
        }
        // ① 覆盖率：拼接后必须原样覆盖原文（丢字检测）。只忽略空白差异，其余一字不许改
        String joined = squeeze(String.join("", chunks));
        String source = squeeze(origin);
        if (!joined.equals(source)) {
            throw new SplitValidationException("切分结果未通过校验：拼接后与原文不一致（原文 %d 字，切分拼回 %d 字），请重新处理"
                    .formatted(source.length(), joined.length()));
        }
        // ② 块数：只有一块 = 它没有切
        if (chunks.size() < 2) {
            throw new SplitValidationException("切分结果未通过校验：模型未给出切分（只返回 1 块），请重新处理");
        }
        // ③ 过碎：低于下限说明语义已被切断，比不切更糟
        for (String chunk : chunks) {
            if (squeeze(chunk).length() < params.minChunkLength()) {
                throw new SplitValidationException("切分结果未通过校验：存在过碎切片（不足 %d 字），请调整切分参数后重新处理"
                        .formatted(params.minChunkLength()));
            }
        }
    }

    /** 去掉全部空白再比对：模型重排换行不该算丢字，改了字才算 */
    private String squeeze(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    // ---------- 第三层：递归切（无条件兜底）----------

    /**
     * 递归切：任何超过 {@code maxLength} 的块**必走**，不管它是结构切还是模型切产出的。
     * 按分隔符优先级逐层降级，每层贪心合并到尽量靠近 {@code targetLength}。
     */
    private List<String> recursiveSplit(String text, SplitParams params) {
        return recursive(text, 0, params);
    }

    private List<String> recursive(String text, int separatorIndex, SplitParams params) {
        if (text.length() <= params.maxLength()) {
            return List.of(text);
        }
        if (separatorIndex >= SEPARATORS.size()) {
            return hardSplit(text, params.maxLength());
        }
        String separator = SEPARATORS.get(separatorIndex);
        List<String> parts = splitKeepingSeparator(text, separator);
        if (parts.size() <= 1) {
            // 这一层分不开（整段没有该分隔符），降级到下一层
            return recursive(text, separatorIndex + 1, params);
        }
        List<String> out = new ArrayList<>();
        for (String merged : merge(parts, params)) {
            if (merged.length() > params.maxLength()) {
                // 单段本身就超长（没有更细的分隔符可用）→ 用下一层继续切
                out.addAll(recursive(merged, separatorIndex + 1, params));
            } else {
                out.add(merged);
            }
        }
        return out;
    }

    /** 用 lookbehind 切分，分隔符留在前一段尾部 */
    private List<String> splitKeepingSeparator(String text, String separator) {
        String[] parts = text.split("(?<=" + Pattern.quote(separator) + ")", -1);
        List<String> kept = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                kept.add(part);
            }
        }
        return kept;
    }

    /** 贪心合并：不超过 max，且尽量凑到 target 就断开 */
    private List<String> merge(List<String> parts, SplitParams params) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (current.length() > 0
                    && (current.length() >= params.targetLength()
                        || current.length() + part.length() > params.maxLength())) {
                merged.add(current.toString());
                current.setLength(0);
            }
            current.append(part);
        }
        if (current.length() > 0) {
            merged.add(current.toString());
        }
        return merged;
    }

    /** 最后手段：没有任何分隔符可用时按 max 硬切（只在整段是无标点的长串时才会走到） */
    private List<String> hardSplit(String text, int maxLength) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxLength) {
            pieces.add(text.substring(i, Math.min(text.length(), i + maxLength)));
        }
        return pieces;
    }

    // ---------- 收尾 ----------

    /**
     * title 回落：结构切给不出标题时用**文档标题**。它不是模型编的（是上传时管理员填的、
     * 与原件同名），溯源指向仍然真实；留空则推荐卡的「判断依据」会是一条没有出处的引用。
     */
    private String resolveTitle(String title, String docTitle) {
        String resolved = title != null && !title.isBlank() ? title.strip() : docTitle;
        if (resolved == null) {
            return null;
        }
        return resolved.length() <= TITLE_MAX ? resolved : resolved.substring(0, TITLE_MAX);
    }

    private ChunkInput toChunk(String title, String text, String docTitle) {
        return new ChunkInput(resolveTitle(title, docTitle), text, 0, List.of());
    }

    /** seq 从 1 连续编号：它是切片在文档内的位置，回放与排查都按它排 */
    private List<ChunkInput> renumber(List<ChunkInput> chunks) {
        List<ChunkInput> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkInput chunk = chunks.get(i);
            out.add(new ChunkInput(chunk.title(), chunk.content(), i + 1, chunk.terms()));
        }
        return out;
    }

    /** 结构切的产物：一段正文（或一张表）＋它继承的标题 */
    private record Piece(String title, String text, boolean table) {
    }
}
