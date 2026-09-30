package com.guide.llm.client;

import com.guide.common.model.LayoutBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 DocumentMind 的结果体映射成版面块。
 *
 * <p>单独成一个纯函数类是为了可测：SDK 的返回体是 {@code Map<String, ?>}（服务端定义结构），
 * 桩一个 Map 就能把映射规则钉住，不必起真客户端。
 *
 * <p><b>为什么额外做了容错</b>：外层包装键实测为 {@code layouts}（2026-09-26 探针复验），
 * 但它是服务端定义的结构、不在 SDK 契约里——这里先按已知键找，找不到就扫一遍 Map，
 * 挑出「元素是 Map 且带 type 字段」的那个列表，键名一变不会静默返回 0 块
 * （那会让每份 pdf 都"解析产出异常"，而真正的原因只是键名不同）。
 *
 * <p>三种块类型来自两个信号：{@code type} 含 title → 标题块；带 {@code numCol} 或 {@code cells}
 * → 表格块；其余 → 正文块。表格**不靠 type 判**——表格的 type 取值没实测过，而
 * 「有没有表格结构字段」是确定的。
 *
 * <p><b>版面类型给不出结论时，回到文字本身看章节编号</b>（2026-09-30 加，做法取自参考实现的
 * {@code _docmind_block_type} + {@code _classify_text_block}）。
 *
 * <p>它补的是 {@code type} 判定的**漏网**。2026-09-30 拿真实解析结果核过一次
 * （「多级标题测试文档.pdf」的 62 个版面块）：28 个章节标题里 DocumentMind 认出 26 个
 * （{@code type=title}），**漏掉的两个都是四级编号**——{@code 1.1.1.1术语约定}、
 * {@code 4.2.1.1四级}，它们的样式与相邻标题并无二致，服务端就是判成了 {@code text}
 * （同级同字号的 {@code 2.1.1.1对症处理}、{@code 3.1.2.1直接四级} 却认出来了）。
 * 边界丢了，切分就只能按长度切、跨小节拼成一片，症状是"召回的内容牛头不对马嘴"。
 */
@Slf4j
final class DocMindLayoutReader {

    /** 尝试的外层包装键（按可能性排序） */
    private static final List<String> LIST_KEYS = List.of("layouts", "layout", "blocks", "paragraphs", "items");

    /**
     * 无歧义的章节编号前缀：`第一章` / `第 2 节` / `一、`。命中即可判标题，不需要附加条件。
     */
    private static final Pattern CN_SECTION = Pattern.compile(
            "第\\s*[一二三四五六七八九十百零0-9]+\\s*[章节条篇]"
                    + "|[一二三四五六七八九十]+、");

    /** 阿拉伯多级编号（`1.1` / `4.2.1.1`）——命中后**还要再判编号之后有没有中文**，见下 */
    private static final Pattern ARABIC_SECTION = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");

    /** 中文（用来判"编号之后还有没有标题文字"） */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * 编号标题的长度上限：再长就已经是一句话，不是标题
     */
    private static final int SECTION_HEADING_MAX_CHARS = 80;

    /**
     * DocumentMind 用字面量 {@code [empty]} 表示"这一块没有文本"。
     *
     * <p>2026-09-30 实测：`O2-扫描OCR验收样例-文字截图.png` 里有一个 {@code type=stamp}（印章）块，
     * 它的 {@code text} 就是这个字面量。**它不是空串**，所以只判 {@code isBlank()} 会把它当正文
     * 写进切片——实测确实进了库（切片正文里明晃晃一行 {@code [empty]}）。这类哨兵值必须当空块丢掉。
     */
    private static final String EMPTY_SENTINEL = "[empty]";

    private DocMindLayoutReader() {
    }

    /**
     * 结果体 → 版面块，按阅读顺序排好。
     *
     * <p><b>{@code index} 是页内序号，排序必须带上 {@code pageNum}</b>——2026-09-26 实测：
     * 一份两页的 PDF，第 2 页的 index 从 0 重新开始。只按 index 排会把两页**逐条交错**
     * （第 1 页的第 1 块、第 2 页的第 1 块、第 1 页的第 2 块……），表现为正文里前后句子
     * 毫无关系地拼在一起。这个错法很隐蔽：每块文本都完整，只有顺序不对，
     * 看单块看不出问题，看检索结果才发现"召回的内容牛头不对马嘴"。
     */
    static List<LayoutBlock> readBlocks(Map<String, ?> data) {
        List<Map<String, ?>> raw = new ArrayList<>(findBlockList(data));
        // 服务端返回的本就是阅读顺序，这里按 (页码, 页内序号) 重排只是兜住乱序的情况；
        // 都缺时是稳定排序，保持服务端给的顺序
        raw.sort(Comparator
                .comparingInt((Map<String, ?> block) -> pageOf(block))
                .thenComparingInt(DocMindLayoutReader::indexOf));
        List<LayoutBlock> blocks = new ArrayList<>(raw.size());
        for (Map<String, ?> block : raw) {
            LayoutBlock mapped = toBlock(block);
            if (mapped != null) {
                blocks.add(mapped);
            }
        }
        return blocks;
    }

    /** 结果体里声明的块总数；拿不到返回 -1（调用方据此判断"还有没有下一页"） */
    static int totalBlocks(Map<String, ?> data) {
        for (String key : List.of("total", "layoutTotal", "totalCount", "count")) {
            Object value = data.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return -1;
    }

    // ---------- 内部 ----------

    private static LayoutBlock toBlock(Map<String, ?> block) {
        String type = firstNonBlank(str(block.get("type")), str(block.get("subType")),
                str(block.get("layoutType")));
        boolean table = block.containsKey("numCol") || block.containsKey("cells");
        String text = firstNonBlank(str(block.get("text")), str(block.get("content")));
        String markdown = firstNonBlank(str(block.get("markdownContent")), str(block.get("markdown")));
        Integer pageNum = intOrNull(block.get("pageNum"));

        if (table) {
            // 表格取 markdown 形态整块入 chunk（一行「症状｜科室｜鉴别方向」本身就是完整语义单元）；
            // 没有 markdown 形态时退回纯文本，不丢内容
            String content = firstNonBlank(markdown, text);
            return isEmptyText(content) ? null : LayoutBlock.table(content, pageNum);
        }
        LayoutBlock.BlockType blockType = LayoutBlock.BlockType.fromExternalType(type);
        // 只有"外部类型没给出结论"时才回到文字上判编号——与参考实现的 _docmind_block_type 同口径
        // （它也是把 _classify_text_block 放在最后那个 else 里，figure/image 等命中后不会再走文本判定）
        if ((blockType == LayoutBlock.BlockType.TEXT || blockType == LayoutBlock.BlockType.UNKNOWN)
                && looksLikeSectionHeading(text)) {
            // 版面类型没识别出标题（编号标题的常见情形）→ 按文字里的章节编号兜底
            log.debug("版面类型 {} 未识别出标题，按章节编号兜底为标题：{}", type, clip(text));
            blockType = LayoutBlock.BlockType.TITLE;
        }
        // 空块先丢，**再**判类型：无文本的块（如 text 为 [empty] 的印章块）不该被算成
        // "未映射的 layout.type" 而打 WARN——那不是类型没映射，是这块本来就没内容
        if (isEmptyText(text)) {
            return null;
        }
        if (blockType == LayoutBlock.BlockType.UNKNOWN) {
            log.warn("DocumentMind 返回未映射的 layout.type：{}，按 unknown 入库", type);
        }
        return LayoutBlock.of(blockType, text, pageNum);
    }

    /** 空块判定：真空白，或 DocumentMind 的"这块没文本"哨兵值 {@code [empty]} */
    private static boolean isEmptyText(String text) {
        return text.isBlank() || EMPTY_SENTINEL.equalsIgnoreCase(text.strip());
    }

    /**
     * 文本级标题兜底：短、且以章节编号开头。
     *
     * <p>阿拉伯编号只认**多级**（`1.1` 不是 `1.`）且**编号之后必须还有中文**，三条判据各挡一类误判：
     * <ol>
     *   <li>单级编号 = 有序列表项（`1. 冻结代码分支，停止合并新的改动。`），与章节标题在文本上
     *       无法区分，只能靠"至少两级"筛掉</li>
     *   <li>编号之后没有中文 = 整行就是个值，不是标题。这条是 2026-09-30 用真实解析结果定的：
     *       **DocumentMind 的 {@code text} 会把编号与标题之间的空格吃掉**
     *       （{@code 1.1 编写目的} → {@code 1.1编写目的}），所以"编号后跟空白"那种判据在这个
     *       服务端的产物上一条都匹配不上、兜底等于没写。改成"后面还有中文"之后，
     *       {@code 10.0.1.1}、{@code 114.114.114.114}、{@code 10.0.1.100/24}、{@code 1.2 kg}
     *       这些"整行是值"的照样挡住，而 {@code 1.1.1.1术语约定} 能救回来</li>
     *   <li>长度上限 80 字：超过就已经是句子了</li>
     * </ol>
     * 代价是**英文标题认不出来**（`1.1 Overview` 编号后没中文）——本项目的语料是中文文档，
     * 且 DocumentMind 自己的样式判定已覆盖大多数标题，兜底只补它漏掉的那些，可以接受。
     *
     * <p>另外**不收「以冒号结尾」**：参考实现把 ≤80 字且以 {@code ：/:} 结尾的行也当标题，
     * 而中文文档里的冒号结尾短行绝大多数是**引导句**（"以下信息必须归为 L4："、"完整上线流程
     * 划分为 8 个阶段："）。误提升的代价不只是多一个边界：标题会被后一条顶掉，于是这一段
     * 真正的章节标题在溯源里就没了。括号编号 {@code （一）}/{@code (1)} 同理不收。
     */
    private static boolean looksLikeSectionHeading(String text) {
        if (text == null) {
            return false;
        }
        String stripped = text.strip();
        if (stripped.isEmpty() || stripped.length() > SECTION_HEADING_MAX_CHARS) {
            return false;
        }
        if (CN_SECTION.matcher(stripped).lookingAt()) {
            return true;
        }
        Matcher arabic = ARABIC_SECTION.matcher(stripped);
        return arabic.lookingAt() && CJK.matcher(stripped.substring(arabic.end())).find();
    }

    private static String clip(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    /** 找块列表：先按已知键取，取不到就扫描（键名未知时不静默返回空） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> findBlockList(Map<String, ?> data) {
        if (data == null) {
            return List.of();
        }
        for (String key : LIST_KEYS) {
            Object value = data.get(key);
            if (value instanceof List<?> list && looksLikeBlocks(list)) {
                return (List<Map<String, ?>>) list;
            }
        }
        for (Object value : data.values()) {
            if (value instanceof List<?> list && looksLikeBlocks(list)) {
                log.debug("DocumentMind 结果体里没有已知的块列表键，按内容扫描命中（键名可能变了）");
                return (List<Map<String, ?>>) list;
            }
        }
        return List.of();
    }

    private static boolean looksLikeBlocks(List<?> list) {
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                return map.containsKey("type") || map.containsKey("text") || map.containsKey("markdownContent");
            }
        }
        return false;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 页码（缺省 0：docx 恒为 0，PDF 从 0 起） */
    private static int pageOf(Map<String, ?> block) {
        Integer page = intOrNull(block.get("pageNum"));
        return page == null ? 0 : page;
    }

    /** 页内序号（缺省排到最后，不抢已有顺序的位置） */
    private static int indexOf(Map<String, ?> block) {
        Integer index = intOrNull(block.get("index"));
        return index == null ? Integer.MAX_VALUE : index;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return "";
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
