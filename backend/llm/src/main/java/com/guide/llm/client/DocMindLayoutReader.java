package com.guide.llm.client;

import com.guide.common.model.LayoutBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 把 DocumentMind 的结果体映射成版面块。
 *
 * <p>单独成一个纯函数类是为了可测：SDK 的返回体是 {@code Map<String, ?>}（服务端定义结构），
 * 桩一个 Map 就能把映射规则钉住，不必起真客户端。
 *
 * <p><b>为什么额外做了容错</b>：外层包装键没有实测过（探针把返回体 dump 出来了，但报告没留档），
 * 已知的是**块内的字段名**（type / text / markdownContent / pageNum / index）。所以这里
 * 先按 {@code layouts} 找，找不到就扫一遍 Map，挑出「元素是 Map 且带 type 字段」的那个列表——
 * 键名猜错时不会静默返回 0 块（那会让每份 pdf 都"解析产出异常"，而真正的原因只是键名不同）。
 *
 * <p>三种块类型来自两个信号：{@code type} 含 title → 标题块；带 {@code numCol} 或 {@code cells}
 * → 表格块；其余 → 正文块。表格**不靠 type 判**——表格的 type 取值没实测过，而
 * 「有没有表格结构字段」是确定的。
 */
@Slf4j
final class DocMindLayoutReader {

    /** 尝试的外层包装键（按可能性排序） */
    private static final List<String> LIST_KEYS = List.of("layouts", "layout", "blocks", "paragraphs", "items");

    private DocMindLayoutReader() {
    }

    /** 单页 → 版面块（按 index 排好；服务端已给阅读顺序，这里只是防御性地再确认一次） */
    static List<LayoutBlock> readBlocks(Map<String, ?> data) {
        List<Map<String, ?>> raw = new ArrayList<>(findBlockList(data));
        // 有 index 就按它排（都缺时是稳定排序，保持服务端给的顺序）
        raw.sort(Comparator.comparingInt(block -> {
            Integer index = intOrNull(block.get("index"));
            return index == null ? Integer.MAX_VALUE : index;
        }));
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
        String type = str(block.get("type"));
        boolean table = block.containsKey("numCol") || block.containsKey("cells");
        String text = firstNonBlank(str(block.get("text")), str(block.get("content")));
        String markdown = firstNonBlank(str(block.get("markdownContent")), str(block.get("markdown")));
        Integer pageNum = intOrNull(block.get("pageNum"));

        if (table) {
            // 表格取 markdown 形态整块入 chunk（一行「症状｜科室｜鉴别方向」本身就是完整语义单元）；
            // 没有 markdown 形态时退回纯文本，不丢内容
            String content = firstNonBlank(markdown, text);
            return content.isBlank() ? null : LayoutBlock.table(content, pageNum);
        }
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("title")) {
            return text.isBlank() ? null : LayoutBlock.title(text, pageNum);
        }
        return text.isBlank() ? null : LayoutBlock.text(text, pageNum);
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
