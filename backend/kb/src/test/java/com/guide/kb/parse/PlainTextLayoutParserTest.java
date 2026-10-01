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

    @Test
    @DisplayName("Markdown 开头的 YAML front matter 剥掉：元数据不进知识库，模型也就没得可丢")
    void stripsYamlFrontMatter() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                ---
                title: "腰痛挂什么科？"
                source: "https://m.sohu.com/a/1081277327"
                author:
                  - "[[39健康网]]"
                tags:
                  - "clippings"
                ---
                腰痛是门诊最常见的主诉之一。

                **一、腰痛应挂什么科？**

                若腰痛伴有下肢放射痛，应前往骨科就诊。
                """, StandardCharsets.UTF_8), "clipping.md");

        assertThat(blocks).extracting(LayoutBlock::text).noneSatisfy(
                text -> assertThat(text).contains("clippings").contains("m.sohu.com"));
        // 剥掉元数据后：正文段 → 粗体小标题（第二档信号）→ 正文段
        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TEXT, LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(blocks.get(0).text()).isEqualTo("腰痛是门诊最常见的主诉之一。");
        assertThat(blocks.get(1).text()).isEqualTo("一、腰痛应挂什么科？");
        assertThat(blocks.get(2).text()).contains("应前往骨科就诊");
    }

    @Test
    @DisplayName("只剥**开头**那一段：正文里的 --- 是分隔线，动了就是删内容")
    void keepsFrontMatterLookingSeparatorsInBody() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                # 标题

                第一段。

                ---

                分隔线之后的第二段。
                """, StandardCharsets.UTF_8), "a.md");

        assertThat(blocks.get(0).text()).isEqualTo("标题");
        assertThat(blocks).extracting(LayoutBlock::text).anySatisfy(
                text -> assertThat(text).contains("第一段").contains("---").contains("第二段"));
    }

    @Test
    @DisplayName("没有 front matter 的 md 与 .txt 一概不动（txt 的 --- 是正文符号）")
    void leavesOthersUntouched() {
        assertThat(parser.parse(stream("# 标题\n\n正文。", StandardCharsets.UTF_8), "a.md"))
                .extracting(LayoutBlock::text).containsExactly("标题", "正文。");

        assertThat(parser.parse(stream("---\n第一段。\n---\n第二段。", StandardCharsets.UTF_8), "a.txt"))
                .extracting(LayoutBlock::text).containsExactly("---\n第一段。\n---\n第二段。");
    }

    @Test
    @DisplayName("front matter 没闭合：不剥（宁可不剥也不要吃掉正文）")
    void keepsUnclosedFrontMatter() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                ---
                title: "没闭合"

                正文照旧。
                """, StandardCharsets.UTF_8), "a.md");

        assertThat(blocks).extracting(LayoutBlock::text).anySatisfy(
                text -> assertThat(text).contains("没闭合").contains("正文照旧"));
    }

    @Test
    @DisplayName("md 标题第二档：整行粗体独占一行 = 标题（公众号/Clippings 的小标题就是这个形态）")
    void treatsBoldOwnLineAsHeading() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                **一、腰痛应挂什么科？**

                骨科——骨骼肌系统问题的首选。

                **风湿免疫科——伴晨僵关节肿痛时**

                腰痛伴晨僵超过 30 分钟，考虑强直性脊柱炎。
                """, StandardCharsets.UTF_8), "a.md");

        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT,
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(blocks.get(0).text()).isEqualTo("一、腰痛应挂什么科？");
        assertThat(blocks.get(2).text()).isEqualTo("风湿免疫科——伴晨僵关节肿痛时");
    }

    @Test
    @DisplayName("md 标题第三档：章节编号（含 `1、` 顿号版），但单级点号是 markdown 列表、不收")
    void treatsSectionNumberAsHeading() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                一、挂号指引

                1、下肢放射痛伴进行性麻木或无力

                1.1 鉴别要点

                1. 冻结代码分支，停止合并新的改动。
                """, StandardCharsets.UTF_8), "a.md");

        // 相邻标题之间没有正文 → 不夹 TEXT 块（只有最后那条列表项是正文）
        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TITLE,
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(blocks.get(0).text()).isEqualTo("一、挂号指引");
        assertThat(blocks.get(1).text()).isEqualTo("1、下肢放射痛伴进行性麻木或无力");
        assertThat(blocks.get(2).text()).isEqualTo("1.1 鉴别要点");
        // 单级 `1.` 是有序列表项，与它上面那条 1、 只差一个字符——判据必须挡得住
        assertThat(blocks.get(3).text()).isEqualTo("1. 冻结代码分支，停止合并新的改动。");
    }

    @Test
    @DisplayName("标题的反例：行内粗体、句号/冒号结尾的强调句、引导句、括号编号、超长粗体")
    void doesNotPromoteFalseHeadings() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                **约90%以上的腰痛属于机械性腰背痛** （如肌肉劳损、韧带拉伤等）。

                **未明确诊断前，不应自行按摩、正骨或反复外用膏药，以免延误病情。**

                参考文献：

                （一）国家中医药管理局名词术语规范推广项目办公室. 女人腰痛挂什么科.

                **这段整行加粗但长达八十余字已经是一整句话而不是小标题了所以必须挡住它不被当成标题从而避免把这一段真正的章节标题顶掉导致溯源里找不到出处这件事很重要所以特意写得长一些再长一些**
                """, StandardCharsets.UTF_8), "a.md");

        assertThat(blocks).extracting(LayoutBlock::type)
                .containsOnly(LayoutBlock.BlockType.TEXT);
    }

    @Test
    @DisplayName("md 表格：整块成 TABLE，去掉分隔行、单元格用全角｜连接；前后正文各自成块")
    void parsesMarkdownTable() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                就诊前请先确认科室范围。

                | 科室 | 位置 |
                | --- | :---: |
                | 骨科 | 门诊楼 3F 东区 |
                | 泌尿外科 | 门诊楼 2F 西区 |

                上表按主诉分区。
                """, StandardCharsets.UTF_8), "a.md");

        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TEXT, LayoutBlock.BlockType.TABLE, LayoutBlock.BlockType.TEXT);
        String table = blocks.get(1).markdown();
        assertThat(table).isEqualTo("""
                科室 ｜ 位置
                骨科 ｜ 门诊楼 3F 东区
                泌尿外科 ｜ 门诊楼 2F 西区""");
        assertThat(table).doesNotContain("---");
    }

    @Test
    @DisplayName("单行含 | 但不是表格（没有分隔行）→ 仍是正文，不误判成 TABLE")
    void keepsPipeLineAsTextWhenNotTable() {
        List<LayoutBlock> blocks = parser.parse(stream("""
                | 这一行只是普通正文里的竖线

                下一段。
                """, StandardCharsets.UTF_8), "a.md");

        assertThat(blocks).extracting(LayoutBlock::type)
                .containsOnly(LayoutBlock.BlockType.TEXT);
    }

    private InputStream stream(String text, Charset charset) {
        return new ByteArrayInputStream(text.getBytes(charset));
    }
}
