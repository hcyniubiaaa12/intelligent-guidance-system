package com.guide.feedback;

import com.guide.feedback.enums.RootCauseKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根因字典契约测试：存储 key 与显示 label 分离，key 由常量名派生。
 *
 * <p>`patient_wrong` 这一项被钉得比其他项死：看板准确率口径靠它排除记录，
 * 它一旦改名而调用方没跟上，口径会**静默**失效（页面不会报错，只是数字慢慢不对）。
 */
class RootCauseKeyTest {

    @Test
    @DisplayName("key = 常量名小写，且全为小写字母与下划线")
    void keyIsLowercaseConstantName() {
        for (RootCauseKey value : RootCauseKey.values()) {
            assertThat(value.getKey()).isEqualTo(value.name().toLowerCase());
            assertThat(value.getKey()).matches("[a-z_]+");
        }
    }

    @Test
    @DisplayName("每项都有中文展示名")
    void everyKeyHasLabel() {
        for (RootCauseKey value : RootCauseKey.values()) {
            assertThat(value.getLabel()).isNotBlank();
        }
    }

    @Test
    @DisplayName("看板口径依赖的 key 钉死为 patient_wrong")
    void patientWrongKeyIsStable() {
        assertThat(RootCauseKey.PATIENT_WRONG.getKey()).isEqualTo("patient_wrong");
        assertThat(RootCauseKey.PATIENT_WRONG.getLabel()).isEqualTo("患者挂错");
    }

    @Test
    @DisplayName("fromKey 容忍大小写与空格，未知值返回空而不是抛异常")
    void fromKeyIsLenient() {
        assertThat(RootCauseKey.fromKey(" retrieval_fail ")).contains(RootCauseKey.RETRIEVAL_FAIL);
        assertThat(RootCauseKey.fromKey("RETRIEVAL_FAIL")).contains(RootCauseKey.RETRIEVAL_FAIL);
        assertThat(RootCauseKey.fromKey("legacy_cause")).isEmpty();
        assertThat(RootCauseKey.fromKey(null)).isEmpty();
        assertThat(RootCauseKey.fromKey("   ")).isEmpty();
    }

    @Test
    @DisplayName("labelOf 对字典外的值原样返回，不丢数据")
    void labelOfFallsBackToKey() {
        assertThat(RootCauseKey.labelOf("chunk_broken")).isEqualTo("切分破碎");
        assertThat(RootCauseKey.labelOf("legacy_cause")).isEqualTo("legacy_cause");
    }
}
