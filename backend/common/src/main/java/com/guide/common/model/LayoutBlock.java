package com.guide.common.model;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 版面块（layout）——解析的产物，切分层的输入。
 *
 * <p>与「切片（chunk）」是两回事：版面块的粒度由**解析器**定，chunk 的粒度由**切分参数**定
 * （见 CONTEXT.md）。本类型是 DocumentMind（pdf/docx/png）与本地解析器（txt/md/html）
 * 两条路径的共同出口——不管谁产出的，切分层看到的都是同一种东西。
 *
 * <p>{@code type} 是解析阶段的版面类型，也作为 {@code kb_chunk.chunk_type} 的持久化枚举。
 * **切分层只关心两件事**：这是不是一个边界（{@link BlockType#TITLE}）、这是不是一整块
 * 不可切的东西（{@link BlockType#TABLE}）；其余类型在切分里都按正文处理。
 * 那为什么还要把 figure / image / formula / code / header / footer 单独建类型——两个理由：
 * <ol>
 *   <li>**可观测**：{@code chunk_type} 落库（MySQL / ES / pgvector 三处），能看出这份文档里
 *       到底有什么。全折成 TEXT 之后，"这份 pdf 的图注被 OCR 混进正文了吗"这类问题就没法查</li>
 *   <li>**后续过滤的抓手**：比如页眉/页脚要不要在检索前丢掉、图片块要不要单独处理，
 *       得先能认出它们。**认得出**和**要不要动**是两件事，本轮只做前者</li>
 * </ol>
 *
 * <p>注意：**文本块没有层级**。DocumentMind 确实给得出字号（实测 H1=15/H2=14/正文=12），
 * 但切分只需要边界、不需要层级树，故不携带该信息——猜层级猜错只会把内容切得更碎。
 *
 * @param type    块类型
 * @param text    纯文本形态
 * @param markdown markdown 形态；**表格块必填**（表格整块入 chunk 靠它），其余块可不填
 * @param pageNum  页码（仅 pdf 有意义；docx 恒为 0），用于产出异常时的人话描述
 */
public record LayoutBlock(BlockType type, String text, String markdown, Integer pageNum) {

    /**
     * 块类型。前三项**改变切分行为**，后六项**不改变**（切分里按正文处理）——判据是
     * 「这是不是边界」与「这是不是整块不可切」，其余差异对切分没有意义。
     */
    public enum BlockType {
        /** 标题块——切分边界的唯一依据 */
        TITLE("title"),
        /** 普通正文块 */
        TEXT("text"),
        /** 表格块——结构化返回，取 markdown 形态整块入 chunk，不参与正文切分 */
        TABLE("table"),
        /** 图表（figure / chart）：与正文同待遇，单独留类型只为可观测 */
        FIGURE("figure"),
        /** 图片（image / picture）：正文里的图片文字由 OCR 带出，形态上就是正文 */
        IMAGE("image"),
        /** 公式（formula / equation） */
        FORMULA("formula"),
        /** 代码块 */
        CODE("code"),
        /** 页眉（header）：`needHeaderFooter=false` 之后正常不该出现，出现说明服务端判了页眉 */
        HEADER("header"),
        /** 页脚（footer） */
        FOOTER("footer"),
        /** 未经过版面类型识别，或外部类型尚未纳入映射 */
        UNKNOWN("unknown");

        @EnumValue
        private final String code;

        BlockType(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        /**
         * 按外部 layout.type 映射，未知值统一兜底为 UNKNOWN。
         *
         * <p>取值表按参考实现（{@code nexus-agent-rag-tools} 的 {@code _docmind_block_type}）补全，
         * **顺序即优先级**，两处不能调换：
         * <ol>
         *   <li>{@code title}/{@code heading}/{@code header1}/{@code header2} 必须排在
         *       {@code header} 之前——否则 {@code header1} 会先被子串 {@code header} 吃掉，
         *       标题变成页眉</li>
         *   <li>{@code footer}/{@code header} 排在最后，它们是"其余都匹配不上"才轮到的最弱信号</li>
         * </ol>
         */
        public static BlockType fromExternalType(String type) {
            if (type == null || type.isBlank()) {
                return UNKNOWN;
            }
            String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
            if (containsAny(normalized, "title", "heading", "header1", "header2")) {
                return TITLE;
            }
            if (normalized.contains("table")) {
                return TABLE;
            }
            if (containsAny(normalized, "figure", "chart")) {
                return FIGURE;
            }
            if (containsAny(normalized, "image", "picture")) {
                return IMAGE;
            }
            if (containsAny(normalized, "formula", "equation")) {
                return FORMULA;
            }
            if (normalized.contains("code")) {
                return CODE;
            }
            if (normalized.contains("footer")) {
                return FOOTER;
            }
            if (normalized.contains("header")) {
                return HEADER;
            }
            if (normalized.equals("text") || normalized.equals("paragraph")) {
                return TEXT;
            }
            return UNKNOWN;
        }

        private static boolean containsAny(String value, String... keywords) {
            for (String keyword : keywords) {
                if (value.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }

    public LayoutBlock {
        text = text == null ? "" : text;
    }

    public static LayoutBlock title(String text, Integer pageNum) {
        return new LayoutBlock(BlockType.TITLE, text, null, pageNum);
    }

    public static LayoutBlock text(String text, Integer pageNum) {
        return new LayoutBlock(BlockType.TEXT, text, null, pageNum);
    }

    public static LayoutBlock unknown(String text, Integer pageNum) {
        return new LayoutBlock(BlockType.UNKNOWN, text, null, pageNum);
    }

    public static LayoutBlock table(String markdown, Integer pageNum) {
        return new LayoutBlock(BlockType.TABLE, markdown, markdown, pageNum);
    }

    /**
     * 按判定出的类型构造（figure/image/formula/code/header/footer）——标题与表格各有自己的
     * 工厂（表格要把正文也放进 markdown 字段）。
     */
    public static LayoutBlock of(BlockType type, String text, Integer pageNum) {
        return new LayoutBlock(type == null ? BlockType.UNKNOWN : type, text, null, pageNum);
    }
}
