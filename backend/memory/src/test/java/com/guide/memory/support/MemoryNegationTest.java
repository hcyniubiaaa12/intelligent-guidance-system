package com.guide.memory.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「提到 / 否定 / 更正」的边界：这组判据同时决定**是否注入**与**是否收回**，
 * 判错一边就会拿被否认的背景作答，或让患者更正过的错误记忆一直留着。
 */
class MemoryNegationTest {

    @Test
    @DisplayName("提到：完整子串与部分词面都算（糖尿病 ↔ 2型糖尿病）")
    void mentionsExactAndPartial() {
        assertThat(MemoryNegation.mentions("我有糖尿病", "糖尿病")).isTrue();
        assertThat(MemoryNegation.mentions("是不是和我的糖尿病有关", "2型糖尿病")).isTrue();
        assertThat(MemoryNegation.mentions("我有没有糖尿病", "2型糖尿病")).isTrue();
        assertThat(MemoryNegation.mentions("最近腰疼", "2型糖尿病")).isFalse();
        assertThat(MemoryNegation.mentions("血糖有点高", "2型糖尿病")).isFalse();
    }

    @Test
    @DisplayName("疑问句里的「是不是 / 有没有」不算否定")
    void interrogativesAreNotNegation() {
        assertThat(MemoryNegation.negated("是不是和我的糖尿病有关", "2型糖尿病")).isFalse();
        assertThat(MemoryNegation.negated("我有没有糖尿病", "2型糖尿病")).isFalse();
        assertThat(MemoryNegation.negated("是否有糖尿病", "2型糖尿病")).isFalse();
    }

    @Test
    @DisplayName("明确否定才拦：没有 / 不是 / 没做过；不确定表述不算")
    void declarativeNegation() {
        assertThat(MemoryNegation.negated("我没有糖尿病", "2型糖尿病")).isTrue();
        assertThat(MemoryNegation.negated("不是糖尿病，是低血糖", "糖尿病")).isTrue();
        assertThat(MemoryNegation.negated("我没做过糖尿病相关检查", "糖尿病")).isTrue();
        assertThat(MemoryNegation.negated("我可能有糖尿病", "糖尿病")).isFalse();
        assertThat(MemoryNegation.negated("我不确定是不是糖尿病", "糖尿病")).isFalse();
        assertThat(MemoryNegation.negated("我有糖尿病", "2型糖尿病")).isFalse();
    }

    @Test
    @DisplayName("家族史 / 并发症不算否认自己患病")
    void externalContextIsNotNegation() {
        assertThat(MemoryNegation.negated("我没有糖尿病家族史", "2型糖尿病")).isFalse();
        assertThat(MemoryNegation.negated("我没有糖尿病并发症", "2型糖尿病")).isFalse();
    }

    @Test
    @DisplayName("否定只作用于紧邻的那条：上一分句的「没有糖尿病」不牵连高血压")
    void negationDoesNotLeakAcrossClauses() {
        assertThat(MemoryNegation.negated("我没有糖尿病，但是我有高血压", "高血压")).isFalse();
        assertThat(MemoryNegation.negated("我没有糖尿病，但是我有高血压", "糖尿病")).isTrue();
    }

    @Test
    @DisplayName("更正：直接否认才收回；更正为「有」、否认的是动作都不收回")
    void retraction() {
        assertThat(MemoryNegation.retracted("说错了我没有2型糖尿病", "2型糖尿病")).isTrue();
        assertThat(MemoryNegation.retracted("之前搞错了，我不是2型糖尿病", "2型糖尿病")).isTrue();
        assertThat(MemoryNegation.retracted("我没有2型糖尿病", "2型糖尿病")).isTrue();
        assertThat(MemoryNegation.retracted("我说错了，其实我有2型糖尿病", "2型糖尿病")).isFalse();
        assertThat(MemoryNegation.retracted("我有没有2型糖尿病", "2型糖尿病")).isFalse();
        // 「没吃 / 没做过」说的是行动，不等于否认患病——误收回会把正确记忆永久停掉
        assertThat(MemoryNegation.retracted("我没吃糖尿病药", "2型糖尿病")).isFalse();
        assertThat(MemoryNegation.retracted("我没做过糖尿病相关检查", "2型糖尿病")).isFalse();
    }
}
