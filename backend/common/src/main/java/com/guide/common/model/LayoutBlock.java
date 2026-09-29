package com.guide.common.model;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 版面块（layout）——解析的产物，切分层的输入。
 *
 * <p>与「切片（chunk）」是两回事：版面块的粒度由**解析器**定，chunk 的粒度由**切分参数**定
 * （见 CONTEXT.md）。本类型是 DocumentMind（pdf/docx/png）与本地解析器（txt/md/html）
 * 两条路径的共同出口——不管谁产出的，切分层看到的都是同一种东西。
 *
 * <p>{@code type} 是解析阶段的版面类型，也作为 {@code kb_chunk.chunk_type} 的持久化枚举：
 * {@code TITLE}/{@code TEXT}/{@code TABLE} 来自结构识别，{@code UNKNOWN} 用于非外部版面识别来源
 * 或暂未映射的外部值。切分层只需要知道「这是不是一个边界」（TITLE）、「这是不是一整块
 * 不可切的东西」（TABLE）。
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

    /** 块类型：切分边界 / 普通正文 / 表格 / 未知 */
    public enum BlockType {
        /** 标题块——切分边界的唯一依据 */
        TITLE("title"),
        /** 普通正文块 */
        TEXT("text"),
        /** 表格块——结构化返回，取 markdown 形态整块入 chunk，不参与正文切分 */
        TABLE("table"),
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

        /** 按外部 layout.type 映射，未知值统一兜底为 UNKNOWN。 */
        public static BlockType fromExternalType(String type) {
            if (type == null || type.isBlank()) {
                return UNKNOWN;
            }
            String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("title")) {
                return TITLE;
            }
            if (normalized.contains("table")) {
                return TABLE;
            }
            if (normalized.equals("text") || normalized.equals("paragraph")) {
                return TEXT;
            }
            return UNKNOWN;
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
}
