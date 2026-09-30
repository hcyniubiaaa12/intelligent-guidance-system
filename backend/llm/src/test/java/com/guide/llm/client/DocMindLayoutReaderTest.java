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
    @DisplayName("多页文档按 (页码, 页内序号) 排：index 是页内序号，只看它会把两页逐条交错")
    void ordersByPageThenIndex() {
        // 实测形态：第 2 页的 index 从 0 重新开始。这份桩刻意乱序，还原"只按 index 排"的错法
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "text", "text", "第二页第一句", "index", 0, "pageNum", 1)),
                block(Map.of("type", "text", "text", "第一页第一句", "index", 0, "pageNum", 0)),
                block(Map.of("type", "text", "text", "第二页第二句", "index", 1, "pageNum", 1)),
                block(Map.of("type", "text", "text", "第一页第二句", "index", 1, "pageNum", 0))
        ));

        List<LayoutBlock> blocks = DocMindLayoutReader.readBlocks(data);

        assertThat(blocks).extracting(LayoutBlock::text).containsExactly(
                "第一页第一句", "第一页第二句", "第二页第一句", "第二页第二句");
    }

    @Test
    @DisplayName("未知 layout.type → unknown，缺失 type 也不丢块")
    void unknownLayoutTypeFallsBackToUnknown() {
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "caption", "text", "图注")),
                block(Map.of("text", "无类型正文"))
        ));

        List<LayoutBlock> blocks = DocMindLayoutReader.readBlocks(data);

        assertThat(blocks).extracting(LayoutBlock::type).containsExactly(
                LayoutBlock.BlockType.UNKNOWN, LayoutBlock.BlockType.UNKNOWN);
    }

    @Test
    @DisplayName("标题族不止 title：heading / header1 也认作标题；type 缺失时回退看 subType")
    void mapsHeadingFamilyToTitle() {
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "heading", "text", "概述")),
                block(Map.of("type", "header1", "text", "内科")),
                block(Map.of("subType", "heading", "text", "呼吸系统"))
        ));

        assertThat(DocMindLayoutReader.readBlocks(data)).extracting(LayoutBlock::type)
                .containsExactly(LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TITLE,
                        LayoutBlock.BlockType.TITLE);
    }

    @Test
    @DisplayName("版面类型只给 text，但文字是章节编号 → 按编号兜底为标题")
    void promotesNumberedHeadingFromText() {
        // 实测形态：「多级标题测试文档」的编号标题全被 DocumentMind 报成 text
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "text", "text", "第一章 概述")),
                block(Map.of("type", "text", "text", "1.1 编写目的")),
                block(Map.of("type", "text", "text", "4.2.1.1 四级")),
                block(Map.of("type", "text", "text", "一、系统名称"))
        ));

        assertThat(DocMindLayoutReader.readBlocks(data)).extracting(LayoutBlock::type)
                .containsExactly(LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TITLE,
                        LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TITLE);
    }

    @Test
    @DisplayName("DocumentMind 的 text 会吃掉编号后的空格，兜底不能靠空白判")
    void promotesNumberedHeadingWithoutSpace() {
        // 真实解析结果里就是连着的（`1.1.1.1 术语约定` → `1.1.1.1术语约定`）：
        // 这两条正是服务端漏判、必须靠兜底救回来的那个四级标题
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "text", "text", "1.1.1.1术语约定")),
                block(Map.of("type", "text", "text", "4.2.1.1四级"))
        ));

        assertThat(DocMindLayoutReader.readBlocks(data)).extracting(LayoutBlock::type)
                .containsExactly(LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TITLE);
    }

    @Test
    @DisplayName("有序列表项与引导句不误判为标题（编号形态可分：单级编号、括号编号、冒号结尾）")
    void doesNotPromoteListItemsOrLeadIns() {
        Map<String, Object> data = Map.of("layouts", List.of(
                // 单级编号 = 有序列表项，不是章节标题
                block(Map.of("type", "text", "text", "1. 冻结代码分支，停止合并新的改动。")),
                block(Map.of("type", "text", "text", "(1) 归档上一版本的应用配置。")),
                // 冒号结尾的引导句：中文文档里绝大多数是引导句，误提升会让真正的章节标题丢溯源
                block(Map.of("type", "text", "text", "以下信息必须归为 L4：")),
                // IP 地址独立成行时与章节编号同形：编号之后没有中文，按"整行是个值"挡掉
                block(Map.of("type", "text", "text", "10.0.1.1")),
                block(Map.of("type", "text", "text", "114.114.114.114")),
                block(Map.of("type", "text", "text", "10.0.1.100/24")),
                // 规格值同理（现状规则曾误判成标题）
                block(Map.of("type", "text", "text", "1.2 kg")),
                // 图注
                block(Map.of("type", "text", "text", "图 1-1 发布流程示意")),
                // 带编号但明显是正文（超过编号标题的长度上限）
                block(Map.of("type", "text", "text", "1.1 本文档用于验证多级标题在文档解析链路中的保留情况，"
                        + "章节编号与标题层级严格对应，本文所称「章节」指由标题划分的语义单元，"
                        + "「层级」指标题在文档结构树中的深度，这一段说明已经明显超过长度上限，"
                        + "应当被当成正文而不是章节标题。"))
        ));

        // 一个都不许被提升成标题（用 containsOnly + 计数，避免加减用例时数错个数）
        assertThat(DocMindLayoutReader.readBlocks(data)).extracting(LayoutBlock::type)
                .hasSize(9)
                .containsOnly(LayoutBlock.BlockType.TEXT);
    }

    @Test
    @DisplayName("表格靠结构字段识别，不靠 type")
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
    @DisplayName("类型表按参考实现补全：figure/image/formula/code/header/footer 各归其类")
    void mapsRemainingExternalTypes() {
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "figure", "text", "图 1 发布流程")),
                block(Map.of("type", "image", "text", "图片文字")),
                block(Map.of("type", "formula", "text", "E = mc^2")),
                block(Map.of("type", "code", "text", "print(1)")),
                block(Map.of("type", "footer", "text", "第 1 页")),
                block(Map.of("type", "header", "text", "内部资料 · 仅供测试"))
        ));

        assertThat(DocMindLayoutReader.readBlocks(data)).extracting(LayoutBlock::type)
                .containsExactly(LayoutBlock.BlockType.FIGURE, LayoutBlock.BlockType.IMAGE,
                        LayoutBlock.BlockType.FORMULA, LayoutBlock.BlockType.CODE,
                        LayoutBlock.BlockType.FOOTER, LayoutBlock.BlockType.HEADER);
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
    @DisplayName("DocumentMind 的 [empty] 哨兵值（印章块）当空块丢弃，不进正文也不打 WARN")
    void dropsEmptySentinelBlock() {
        // 实测形态：文字截图 PNG 里 type=stamp 的印章块，text 就是字面量 [empty] —— 它不是空串，
        // 只判 isBlank() 会把它当正文写进切片（实测确实进过库）
        Map<String, Object> data = Map.of("layouts", List.of(
                block(Map.of("type", "stamp", "text", "[empty]\n")),
                block(Map.of("type", "text", "text", "   ")),
                block(Map.of("type", "text", "text", "正文"))
        ));

        List<LayoutBlock> blocks = DocMindLayoutReader.readBlocks(data);

        assertThat(blocks).extracting(LayoutBlock::text).containsExactly("正文");
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
