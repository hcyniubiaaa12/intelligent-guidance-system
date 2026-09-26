package com.guide.llm.client;

import com.guide.common.model.LayoutBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentMind 结果体 → 版面块的映射。
 *
 * <p>桩用的是**实测到的块字段名**（type / text / markdownContent / pageNum / index），
 * 外层包装键则刻意测了两种：已知的 {@code layouts} 与未知键——后者靠扫描兜住，
 * 避免键名一变就静默返回 0 块（那会让每份 pdf 都"解析产出异常"，而真正的原因只是键名不同）。
 */
class DocMindLayoutReaderTest {

    @Test
    @DisplayName("title 块 → 标题块，text 块 → 正文块，按 index 排序")
    void mapsTitleAndTextBlocks() {
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "text", "text", "正文在后但 index 大", "index", 2)),
                block(Map.of("type", "title", "text", "第一章", "index", 1))
        ));

        List<LayoutBlock> blocks = DocMindLayoutReader.readBlocks(data);

        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(blocks.get(0).text()).isEqualTo("第一章");
    }

    @Test
    @DisplayName("表格：靠 numCol/cells 判类型，取 markdown 形态")
    void mapsTableByStructureNotByType() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("type", "text");
        table.put("numCol", 3);
        table.put("cells", List.of(Map.of("cellId", 0)));
        table.put("markdownContent", "| 症状 | 科室 |\n| --- | --- |\n| 胸闷 | 心血管内科 |");
        Map<String, Object> data = Map.of("layouts", List.of(block(table)));

        List<LayoutBlock> blocks = DocMindLayoutReader.readBlocks(data);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).type()).isEqualTo(LayoutBlock.BlockType.TABLE);
        assertThat(blocks.get(0).markdown()).contains("| 胸闷 | 心血管内科 |");
    }

    @Test
    @DisplayName("外层键名不认识时按内容扫描，不静默返回 0 块")
    void findsBlocksUnderUnknownKey() {
        Map<String, Object> data = Map.of("somethingElse", List.of(
                block(Map.of("type", "title", "text", "标题"))
        ));

        assertThat(DocMindLayoutReader.readBlocks(data)).hasSize(1);
    }

    @Test
    @DisplayName("空白块被丢弃；页码随块带出（产出异常的人话要用它）")
    void skipsBlankBlocksAndKeepsPageNum() {
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "text", "text", "   ")),
                block(Map.of("type", "text", "text", "有内容", "pageNum", 3))
        ));

        List<LayoutBlock> blocks = DocMindLayoutReader.readBlocks(data);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).pageNum()).isEqualTo(3);
    }

    @Test
    @DisplayName("空结果体 / null 不炸，返回空列表（上层按产出异常处理）")
    void toleratesEmptyResult() {
        assertThat(DocMindLayoutReader.readBlocks(null)).isEmpty();
        assertThat(DocMindLayoutReader.readBlocks(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("声明总块数：有就取，没有给 -1")
    void readsDeclaredTotal() {
        assertThat(DocMindLayoutReader.totalBlocks(Map.of("total", 42))).isEqualTo(42);
        assertThat(DocMindLayoutReader.totalBlocks(Map.of())).isEqualTo(-1);
    }

    /** 块是 Map，但 value 类型混杂 → 用 Map<String,Object> 造 */
    private Map<String, ?> block(Map<String, Object> content) {
        return new LinkedHashMap<>(content);
    }
}
