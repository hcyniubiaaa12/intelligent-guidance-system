package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * html 解析（Tika 的唯一用处）：标题层级要吃下来，表格要成为可入 chunk 的 markdown 表格。
 */
class HtmlLayoutParserTest {

    private final HtmlLayoutParser parser = new HtmlLayoutParser();

    @Test
    @DisplayName("h1–h6 收成标题块，段落收成正文块")
    void extractsHeadingsAndParagraphs() {
        List<LayoutBlock> blocks = parser.parse(html("""
                <html><body>
                  <h1>心血管内科</h1>
                  <p>劳力性胸闷多见于冠心病。</p>
                  <h2>鉴别要点</h2>
                  <p>需与呼吸系统疾病鉴别。</p>
                  <script>console.log('不该出现')</script>
                </body></html>
                """), "dept.html");

        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT,
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(blocks.get(1).text()).isEqualTo("劳力性胸闷多见于冠心病。");
        assertThat(blocks).noneSatisfy(b -> assertThat(b.text()).contains("不该出现"));
    }

    @Test
    @DisplayName("表格收成 markdown 表格块——分诊知识里信息密度最高的部分不能丢")
    void convertsTableToMarkdown() {
        List<LayoutBlock> blocks = parser.parse(html("""
                <html><body>
                  <h2>症状对照</h2>
                  <table>
                    <tr><th>症状</th><th>首诊科室</th></tr>
                    <tr><td>劳力性胸闷</td><td>心血管内科</td></tr>
                  </table>
                </body></html>
                """), "dept.html");

        LayoutBlock table = blocks.stream()
                .filter(b -> b.type() == LayoutBlock.BlockType.TABLE).findFirst().orElseThrow();
        assertThat(table.markdown()).contains("| 症状 | 首诊科室 |");
        assertThat(table.markdown()).contains("| --- | --- |");
        assertThat(table.markdown()).contains("| 劳力性胸闷 | 心血管内科 |");
    }

    @Test
    @DisplayName("空 html → 空列表（调用方按产出异常处理）")
    void emptyHtmlYieldsNothing() {
        assertThat(parser.parse(html("<html><body></body></html>"), "empty.html")).isEmpty();
    }

    private InputStream html(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
