package com.guide.kb.split;

import com.guide.common.model.LayoutBlock;
import com.guide.kb.dto.ChunkInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切分三层的行为约束。用桩替换模型切——切分策略本身不该把模型调用焊死在里面。
 */
class DocSplitterTest {

    private static final SplitParams PARAMS = new SplitParams(100, 200, 300);

    private final StubModelSplitter modelSplitter = new StubModelSplitter();
    private final DocSplitter splitter = new DocSplitter(modelSplitter);

    @Test
    @DisplayName("结构切：标题自己成一片（chunk_type=title），正文归到最近的标题下")
    void structureSplitByHeadings() {
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("心血管内科分诊知识", null),
                LayoutBlock.text("劳力性胸闷多见于冠心病。", null),
                LayoutBlock.title("呼吸内科", null),
                LayoutBlock.text("咳嗽伴发热建议首诊呼吸内科。", null)
        ), PARAMS, "知识库文档");

        assertThat(chunks).hasSize(4);
        // 标题片：标题即正文；正文片：带着标题但正文里不重复它
        assertThat(chunks).extracting(ChunkInput::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT,
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(chunks.get(0).title()).isEqualTo("心血管内科分诊知识");
        assertThat(chunks.get(0).content()).isEqualTo("心血管内科分诊知识");
        assertThat(chunks.get(1).title()).isEqualTo("心血管内科分诊知识");
        assertThat(chunks.get(1).content()).isEqualTo("劳力性胸闷多见于冠心病。");
        assertThat(chunks.get(2).type()).isEqualTo(LayoutBlock.BlockType.TITLE);
        assertThat(chunks.get(2).content()).isEqualTo("呼吸内科");
        assertThat(chunks.get(3).title()).isEqualTo("呼吸内科");
    }

    @Test
    @DisplayName("标题片与正文片成对出现：每个标题恰好一条标题片，不重复不遗漏")
    void everyHeadingBecomesExactlyOneTitleChunk() {
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("4.2 深层编号", null),
                // 连续标题：中间没有正文
                LayoutBlock.title("4.2.1 三级", null),
                LayoutBlock.text("形如 4.2.1 的三级标题。", null)
        ), PARAMS, "文档");

        assertThat(chunks).extracting(ChunkInput::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(chunks).extracting(ChunkInput::content).containsExactly(
                "4.2 深层编号", "4.2.1 三级", "形如 4.2.1 的三级标题。");
    }

    @Test
    @DisplayName("表格块整块入 chunk，不参与正文切分，title 取最近标题")
    void tableBecomesOwnChunk() {
        String markdown = "| 症状 | 首诊科室 |\n| --- | --- |\n| 劳力性胸闷 | 心血管内科 |";
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("鉴别表", null),
                LayoutBlock.text("下表用于快速对照。", null),
                LayoutBlock.table(markdown, null)
        ), PARAMS, "知识库文档");

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(2).content()).isEqualTo(markdown);
        assertThat(chunks.get(2).title()).isEqualTo("鉴别表");
        assertThat(chunks.get(2).type()).isEqualTo(LayoutBlock.BlockType.TABLE);
    }

    @Test
    @DisplayName("递归切：超长正文按分隔符切开，每块不超上限，正文拼回与原文一致")
    void recursiveSplitKeepsEveryChar() {
        String text = ("一".repeat(90) + "。").repeat(5);
        // 有标题 → 不触发模型切，这一条只考递归切
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("标题", null),
                LayoutBlock.text(text, null)), PARAMS, "文档");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(200));
        // 标题片(=标题)之外，正文片拼回来必须与原文一字不差
        assertThat(String.join("", bodyContents(chunks))).isEqualTo(text);
        assertThat(modelSplitter.calls).isZero();
    }

    @Test
    @DisplayName("递归切：整段没有分隔符时按上限硬切，不丢字")
    void recursiveSplitHardCutsWhenNoSeparator() {
        String text = "啊".repeat(500);
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("标题", null),
                LayoutBlock.text(text, null)), PARAMS, "文档");

        assertThat(chunks).hasSize(4); // 1 条标题片 + 3 条正文片
        assertThat(String.join("", bodyContents(chunks))).isEqualTo(text);
    }

    /** 正文片（排除标题片）的 content，用于"拼回原文"这类校验 */
    private List<String> bodyContents(List<ChunkInput> chunks) {
        return chunks.stream()
                .filter(c -> c.type() != LayoutBlock.BlockType.TITLE)
                .map(ChunkInput::content)
                .toList();
    }

    @Test
    @DisplayName("模型切只在「无标题且够长」时出场，只给边界")
    void modelSplitOnlyForLongUntitledText() {
        String text = "一".repeat(320);
        modelSplitter.result = List.of("一".repeat(160), "一".repeat(160));

        List<ChunkInput> chunks = splitter.split(List.of(LayoutBlock.text(text, null)), PARAMS, "文档");

        assertThat(modelSplitter.calls).isEqualTo(1);
        assertThat(chunks).hasSize(2);
        // 模型不给 title —— title 回落文档标题，不是模型编的
        assertThat(chunks).allSatisfy(c -> assertThat(c.title()).isEqualTo("文档"));
    }

    @Test
    @DisplayName("有标题的长文本不走模型切：结构切已经给了边界")
    void titledTextNeverCallsModel() {
        String text = "一".repeat(320);
        splitter.split(List.of(
                LayoutBlock.title("标题", null),
                LayoutBlock.text(text, null)
        ), PARAMS, "文档");

        assertThat(modelSplitter.calls).isZero();
    }

    @Test
    @DisplayName("产出校验：模型切结果拼不回原文 → 抛（不能编一份看起来合理的切分）")
    void modelSplitValidationRejectsLostText() {
        modelSplitter.result = List.of("一".repeat(160), "二".repeat(160));

        assertThatThrownBy(() -> splitter.split(
                List.of(LayoutBlock.text("一".repeat(320), null)), PARAMS, "文档"))
                .isInstanceOf(SplitValidationException.class)
                .hasMessageContaining("与原文不一致");
    }

    @Test
    @DisplayName("产出校验：只返回一块 = 它没有切")
    void modelSplitValidationRejectsSingleChunk() {
        modelSplitter.result = List.of("一".repeat(320));

        assertThatThrownBy(() -> splitter.split(
                List.of(LayoutBlock.text("一".repeat(320), null)), PARAMS, "文档"))
                .isInstanceOf(SplitValidationException.class)
                .hasMessageContaining("未给出切分");
    }

    @Test
    @DisplayName("产出校验：过碎切片 → 抛（语义已被切断）")
    void modelSplitValidationRejectsTinyChunks() {
        modelSplitter.result = List.of("一".repeat(10), "一".repeat(310));

        assertThatThrownBy(() -> splitter.split(
                List.of(LayoutBlock.text("一".repeat(320), null)), PARAMS, "文档"))
                .isInstanceOf(SplitValidationException.class)
                .hasMessageContaining("过碎");
    }

    @Test
    @DisplayName("模型切产出超长块时，递归切仍兜底（无条件）")
    void recursiveStillAppliesToModelChunks() {
        modelSplitter.result = List.of("一".repeat(60), "二".repeat(260));

        List<ChunkInput> chunks = splitter.split(
                List.of(LayoutBlock.text("一".repeat(60) + "二".repeat(260), null)), PARAMS, "文档");

        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(200));
        assertThat(String.join("", chunks.stream().map(ChunkInput::content).toList()))
                .isEqualTo("一".repeat(60) + "二".repeat(260));
    }

    @Test
    @DisplayName("seq 从 1 连续编号；title 超长截到 255")
    void seqAndTitleTruncation() {
        String longTitle = "标".repeat(300);
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title(longTitle, null),
                LayoutBlock.text("正文一。", null),
                LayoutBlock.title("第二节", null),
                LayoutBlock.text("正文二。", null)
        ), PARAMS, "文档");

        assertThat(chunks).extracting(ChunkInput::seq).containsExactly(1, 2, 3, 4);
        // 标题超长截到 255（标题片与紧随的正文片都按同一口径截）
        assertThat(chunks.get(0).title()).hasSize(255);
        assertThat(chunks.get(1).title()).hasSize(255);
    }

    @Test
    @DisplayName("标题片与正文片交替：连续标题各自成片，浅层标题的文字不会凭空消失")
    void consecutiveHeadingsEachBecomeChunks() {
        // 实测形态（多级标题测试文档）：4.2 直接跟 4.2.1，中间没有正文
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("4.2 深层编号", null),
                LayoutBlock.title("4.2.1 三级", null),
                LayoutBlock.text("形如 4.2.1 的三级标题。", null)
        ), PARAMS, "文档");

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).title()).isEqualTo("4.2 深层编号");
        assertThat(chunks.get(0).content()).isEqualTo("4.2 深层编号");
        assertThat(chunks.get(1).content()).isEqualTo("4.2.1 三级");
        // 正文片带最近标题（归属靠 title 列），正文里不重复标题文字
        assertThat(chunks.get(2).title()).isEqualTo("4.2.1 三级");
        assertThat(chunks.get(2).content()).isEqualTo("形如 4.2.1 的三级标题。");
    }

    @Test
    @DisplayName("表格片也带最近标题，且标题只出一条标题片（不重复、不遗漏）")
    void titleChunkAndTableChunkCoexist() {
        String markdown = "| 症状 | 首诊科室 |\n| --- | --- |\n| 劳力性胸闷 | 心血管内科 |";
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("鉴别表", null),
                LayoutBlock.table(markdown, null),
                LayoutBlock.title("下一节", null),
                LayoutBlock.text("正文。", null)
        ), PARAMS, "文档");

        assertThat(chunks).hasSize(4);
        assertThat(chunks).extracting(ChunkInput::type).containsExactly(
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TABLE,
                LayoutBlock.BlockType.TITLE, LayoutBlock.BlockType.TEXT);
        assertThat(chunks).extracting(ChunkInput::title)
                .containsExactly("鉴别表", "鉴别表", "下一节", "下一节");
    }

    @Test
    @DisplayName("空块与空白块被跳过；一个都没切出来时返回空列表")
    void skipsBlankBlocks() {
        assertThat(splitter.split(List.of(
                LayoutBlock.text("   ", null),
                LayoutBlock.title("", null)
        ), PARAMS, "文档")).isEmpty();
    }

    @Test
    @DisplayName("参数归一：上限不得小于目标，模型门槛不得低于上限")
    void paramsNormalised() {
        SplitParams params = new SplitParams(500, 100, 10);
        assertThat(params.maxLength()).isEqualTo(500);
        assertThat(params.modelMinLength()).isEqualTo(500);
        assertThat(SplitParams.defaults().minChunkLength()).isEqualTo(100);
    }

    @Test
    @DisplayName("枚举里每一种块类型都被切分收下（新增类型而 switch 没跟上会静默丢内容）")
    void everyBlockTypeIsHandled() {
        for (LayoutBlock.BlockType type : LayoutBlock.BlockType.values()) {
            String marker = "内容-" + type.name();
            LayoutBlock block = type == LayoutBlock.BlockType.TABLE
                    ? LayoutBlock.table("| " + marker + " |", null)
                    : LayoutBlock.of(type, marker, null);

            List<ChunkInput> chunks = splitter.split(List.of(block), PARAMS, "文档");

            assertThat(chunks).as("块类型 %s 应产出切片", type).isNotEmpty();
            assertThat(chunks).as("块类型 %s 的内容应被保留", type)
                    .anySatisfy(chunk -> assertThat(chunk.title() + "|" + chunk.content()).contains(marker));
        }
    }

    @Test
    @DisplayName("图/公式/代码/页眉页脚按正文处理：内容进正文，chunk_type 保留各自类型")
    void nonBoundaryTypesGoToBody() {
        List<ChunkInput> chunks = splitter.split(List.of(
                LayoutBlock.title("产品简介", null),
                LayoutBlock.of(LayoutBlock.BlockType.FIGURE, "图 1 发布流程示意", null),
                LayoutBlock.of(LayoutBlock.BlockType.CODE, "print(1)", null),
                LayoutBlock.of(LayoutBlock.BlockType.FOOTER, "第 1 页", null)
        ), PARAMS, "文档");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).title()).isEqualTo("产品简介");
        assertThat(chunks.get(1).content()).contains("图 1 发布流程示意", "print(1)", "第 1 页");
        // 混合正文取**最后一块**的类型：行为与本轮改动前一致（那时它们都是 UNKNOWN）
        assertThat(chunks.get(1).type()).isEqualTo(LayoutBlock.BlockType.FOOTER);
    }

    /** 模型切桩：只记录调用次数并回放预设结果 */
    private static final class StubModelSplitter implements ModelSplitter {

        private List<String> result = new ArrayList<>();
        private int calls;

        @Override
        public List<String> split(String text, SplitParams params) {
            calls++;
            return result;
        }
    }
}
