package com.guide.feedback;

import com.guide.feedback.enums.BucketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桶状态契约：入库编码、终态集合、URL 编码解析。
 *
 * <p>{@code fromCode} 的存在理由是管理端筛选参数走 URL——传的是 {@code approved}（小写编码）
 * 而不是 {@code APPROVED}（常量名）。Spring 默认的枚举绑定只认常量名，所以必须自己解析；
 * 解析不了时**返回空而不是抛异常**，由调用方决定拒绝还是忽略（当前是拒绝，见 ReviewAdminController）。
 */
class BucketStatusTest {

    @Test
    @DisplayName("入库编码 = 常量名小写，且全为小写字母")
    void codeIsLowercaseConstantName() {
        for (BucketStatus value : BucketStatus.values()) {
            assertThat(value.getCode()).isEqualTo(value.name().toLowerCase());
            assertThat(value.getCode()).matches("[a-z_]+");
        }
    }

    @Test
    @DisplayName("终态三种 = 已审核 / 已驳回 / 已忽略，不含 monitoring 与 pending")
    void terminalIsTheThreeReviewedStates() {
        assertThat(BucketStatus.TERMINAL)
                .containsExactly(BucketStatus.APPROVED, BucketStatus.REJECTED, BucketStatus.DISMISSED);
        assertThat(BucketStatus.TERMINAL).doesNotContain(BucketStatus.MONITORING, BucketStatus.PENDING);
    }

    @Test
    @DisplayName("fromCode 容忍大小写与空格，未知值/空值返回空而不是抛异常")
    void fromCodeIsLenient() {
        assertThat(BucketStatus.fromCode("approved")).contains(BucketStatus.APPROVED);
        assertThat(BucketStatus.fromCode(" APPROVED ")).contains(BucketStatus.APPROVED);
        assertThat(BucketStatus.fromCode("dismissed")).contains(BucketStatus.DISMISSED);
        assertThat(BucketStatus.fromCode("legacy_state")).isEmpty();
        assertThat(BucketStatus.fromCode(null)).isEmpty();
        assertThat(BucketStatus.fromCode("   ")).isEmpty();
    }

    @Test
    @DisplayName("fromCode 能解析终态集合里的每一个值（筛选下拉就是照这批编码发请求的）")
    void everyTerminalCodeParsesBack() {
        for (BucketStatus status : BucketStatus.TERMINAL) {
            assertThat(BucketStatus.fromCode(status.getCode())).contains(status);
        }
    }
}