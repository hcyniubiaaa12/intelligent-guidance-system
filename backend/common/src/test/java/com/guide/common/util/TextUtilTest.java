package com.guide.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 截断口径统一的意义：这些字符串会一路走到**患者看到的推荐卡**和**审核页的证据快照**。
 * 散着各写一遍，迟早出现"有的加 …、有的加 ...、有的直接砍断"。
 */
class TextUtilTest {

    @Test
    @DisplayName("超长才截断，并补省略号")
    void abbreviatesOnlyWhenTooLong() {
        assertEquals("12345...", TextUtil.abbreviate("1234567890", 5));
        assertEquals("12345", TextUtil.abbreviate("12345", 5));
    }

    @Test
    @DisplayName("null 当空串（少一层判空）")
    void nullIsEmpty() {
        assertEquals("", TextUtil.abbreviate(null, 10));
    }

    @Test
    @DisplayName("按字符截：中文不会被截成半个字")
    void countsCharactersNotBytes() {
        assertEquals("头痛伴发热...", TextUtil.abbreviate("头痛伴发热、颈项强直需急诊", 5));
    }
}
