package com.guide.kb.dto;

import com.guide.common.model.LayoutBlock;

import java.util.List;

/**
 * 待入库的知识片段（入库流水线切分产物 / 回流合成 chunk / 种子语料共用）。
 *
 * @param title   切片标题（溯源引用）
 * @param content 切片正文
 * @param seq     切片序号
 * @param terms   医学术语（随 chunk 入 ES，供 terms 聚合产出白名单候选池）
 * @param type    版面类型；非外部版面识别来源使用 UNKNOWN
 */
public record ChunkInput(String title, String content, int seq, List<String> terms, LayoutBlock.BlockType type) {

    public ChunkInput(String title, String content, int seq, List<String> terms) {
        this(title, content, seq, terms, LayoutBlock.BlockType.UNKNOWN);
    }

    public ChunkInput {
        terms = terms == null ? List.of() : List.copyOf(terms);
        type = type == null ? LayoutBlock.BlockType.UNKNOWN : type;
    }
}
