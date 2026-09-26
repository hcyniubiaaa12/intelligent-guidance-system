package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * txt / md 的原生解析。两条容易被忽略的性质：**Markdown 自带的标题层级要吃下来**，
 * 以及**GBK 文档不能静默变成乱码**。
 */
class PlainTextLayoutParserTest {

    private final PlainTextLayoutParser parser = new PlainTextLayoutParser();

    @Test
    @DisplayName("Markdown：标题行 = 标题块，其余按空行分段")
    void parsesMarkdownHeadings() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                # 心血管内科分诊知识

                劳力性胸闷多见于冠心病。

                ## 鉴别要点

                需与呼吸系统疾病鉴别。
                """, StandardCharsets.UTF_8), "心内科.md");

        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT,
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(blocks.get(0).text()).isEqualTo("心血管内科分诊知识");
        assertThat(blocks.get(2).text()).isEqualTo("鉴别要点");
    }

    @Test
    @DisplayName("纯文本：按空行分段，没有标题块——切分层据此判定「结构切给不出边界」")
    void parsesPlainTextAsParagraphs() {
        List<LayoutBlock> blocks = parser.parse(
                stream("第一段。\n\n第二段。", StandardCharsets.UTF_8), "notes.txt");

        assertThat(blocks).hasSize(2);
        assertThat(blocks).allSatisfy(b -> assertThat(b.type()).isEqualTo(LayoutBlock.BlockType.TEXT));
        assertThat(blocks.get(1).text()).isEqualTo("第二段。");
    }

    @Test
    @DisplayName("GBK 文档回落到 GBK 解码，不产出乱码语料")
    void fallsBackToGbk() {
        byte[] gbk = "胸闷心悸，建议首诊心血管内科。".getBytes(Charset.forName("GBK"));
        List<LayoutBlock> blocks = parser.parse(new ByteArrayInputStream(gbk), "note.txt");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("胸闷心悸，建议首诊心血管内科。");
    }

    @Test
    @DisplayName("UTF-8 BOM 被去掉，不混进第一个切片")
    void stripsBom() {
        byte[] withBom = "﻿# 标题".getBytes(StandardCharsets.UTF_8);
        List<LayoutBlock> blocks = parser.parse(new ByteArrayInputStream(withBom), "a.md");

        assertThat(blocks.get(0).text()).isEqualTo("标题");
    }

    @Test
    @DisplayName("空文件 → 空列表（调用方按产出异常处理）")
    void emptyFileYieldsNothing() {
        assertThat(parser.parse(stream("   \n\n  ", StandardCharsets.UTF_8), "a.txt")).isEmpty();
    }

    private InputStream stream(String text, Charset charset) {
        return new ByteArrayInputStream(text.getBytes(charset));
    }
}
