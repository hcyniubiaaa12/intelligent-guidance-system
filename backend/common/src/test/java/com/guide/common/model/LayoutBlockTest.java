package com.guide.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块类型判定表（{@link LayoutBlock.BlockType#fromExternalType}）。
 *
 * <p>取值表按参考实现（`nexus-agent-rag-tools` 的 `_docmind_block_type`）补全，
 * **顺序即优先级**，两条顺序敏感点各有一个用例钉住。
 */
class LayoutBlockTest {

    @Test
    @DisplayName("标题族：title / heading / header1 / header2 都是标题（header1 不能被子串 header 吃掉）")
    void headingFamilyMapsToTitle() {
        assertThat(LayoutBlock.BlockType.fromExternalType("title")).isEqualTo(LayoutBlock.BlockType.TITLE);
        assertThat(LayoutBlock.BlockType.fromExternalType("heading")).isEqualTo(LayoutBlock.BlockType.TITLE);
        assertThat(LayoutBlock.BlockType.fromExternalType("header1")).isEqualTo(LayoutBlock.BlockType.TITLE);
        assertThat(LayoutBlock.BlockType.fromExternalType("Header2")).isEqualTo(LayoutBlock.BlockType.TITLE);
        // 只有纯 header 才是页眉
        assertThat(LayoutBlock.BlockType.fromExternalType("header")).isEqualTo(LayoutBlock.BlockType.HEADER);
    }

    @Test
    @DisplayName("其余外部类型各归其类（含别名 chart/picture/equation）")
    void remainingTypesMap() {
        assertThat(LayoutBlock.BlockType.fromExternalType("table")).isEqualTo(LayoutBlock.BlockType.TABLE);
        assertThat(LayoutBlock.BlockType.fromExternalType("figure")).isEqualTo(LayoutBlock.BlockType.FIGURE);
        assertThat(LayoutBlock.BlockType.fromExternalType("chart")).isEqualTo(LayoutBlock.BlockType.FIGURE);
        assertThat(LayoutBlock.BlockType.fromExternalType("image")).isEqualTo(LayoutBlock.BlockType.IMAGE);
        assertThat(LayoutBlock.BlockType.fromExternalType("picture")).isEqualTo(LayoutBlock.BlockType.IMAGE);
        assertThat(LayoutBlock.BlockType.fromExternalType("formula")).isEqualTo(LayoutBlock.BlockType.FORMULA);
        assertThat(LayoutBlock.BlockType.fromExternalType("equation")).isEqualTo(LayoutBlock.BlockType.FORMULA);
        assertThat(LayoutBlock.BlockType.fromExternalType("code")).isEqualTo(LayoutBlock.BlockType.CODE);
        assertThat(LayoutBlock.BlockType.fromExternalType("footer")).isEqualTo(LayoutBlock.BlockType.FOOTER);
        assertThat(LayoutBlock.BlockType.fromExternalType("text")).isEqualTo(LayoutBlock.BlockType.TEXT);
        assertThat(LayoutBlock.BlockType.fromExternalType("paragraph")).isEqualTo(LayoutBlock.BlockType.TEXT);
    }

    @Test
    @DisplayName("表外的类型与空值统一兜底为 unknown（不猜）")
    void unmappedFallsBackToUnknown() {
        // caption 实测出现过，但参考实现的表里没有它 —— 照表判，不要按"看着像"归类
        assertThat(LayoutBlock.BlockType.fromExternalType("caption")).isEqualTo(LayoutBlock.BlockType.UNKNOWN);
        assertThat(LayoutBlock.BlockType.fromExternalType("  ")).isEqualTo(LayoutBlock.BlockType.UNKNOWN);
        assertThat(LayoutBlock.BlockType.fromExternalType(null)).isEqualTo(LayoutBlock.BlockType.UNKNOWN);
    }

    @Test
    @DisplayName("of() 保留类型，null 类型兜底 unknown；表格的正文同时落在 text 与 markdown")
    void factoriesKeepType() {
        assertThat(LayoutBlock.of(LayoutBlock.BlockType.FIGURE, "图 1", 2).type())
                .isEqualTo(LayoutBlock.BlockType.FIGURE);
        assertThat(LayoutBlock.of(null, "x", null).type()).isEqualTo(LayoutBlock.BlockType.UNKNOWN);
        LayoutBlock table = LayoutBlock.table("| a |", 1);
        assertThat(table.text()).isEqualTo("| a |");
        assertThat(table.markdown()).isEqualTo("| a |");
    }
}
