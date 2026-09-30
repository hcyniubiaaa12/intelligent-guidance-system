package com.guide.chat.service;

import com.guide.common.config.PromptProperties;
import com.guide.kb.service.MedicalTermService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则硬门槛单测：无效输入全轮次拦、应答轮有效短回答不被误杀、首条门槛口径不变。
 */
class SufficiencyRuleTest {

    private static final String QUESTION = "请补充一下部位、感觉和持续时间。";
    private static final String QUESTION_REPEAT = "我还是没听到症状相关的信息，可以这样说：胸口闷三天了。";

    private final MedicalTermService terms = mock(MedicalTermService.class);

    private SufficiencyRule rule() {
        PromptProperties prompts = new PromptProperties();
        prompts.getChat().setTemplateQuestion(QUESTION);
        prompts.getChat().setTemplateQuestionRepeat(QUESTION_REPEAT);
        return new SufficiencyRule(terms, prompts);
    }

    @Test
    @DisplayName("无效输入：语气词、寒暄、说不出、纯符号")
    void detectsNoise() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("嗯")).isTrue();
        assertThat(rule.noSignal("在吗")).isTrue();
        assertThat(rule.noSignal("不知道")).isTrue();
        assertThat(rule.noSignal("随便")).isTrue();
        assertThat(rule.noSignal("。。。")).isTrue();
        assertThat(rule.noSignal("   ")).isTrue();
        assertThat(rule.noSignal(null)).isTrue();
    }

    @Test
    @DisplayName("英文寒暄同样算无效：大小写、尾部标点、多空格都不影响")
    void detectsEnglishGreetings() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("hello")).isTrue();
        assertThat(rule.noSignal("Hello")).isTrue();
        assertThat(rule.noSignal("HELLO!")).isTrue();
        assertThat(rule.noSignal(" hi ")).isTrue();
        assertThat(rule.noSignal("hi")).isTrue();
        assertThat(rule.noSignal("Hey,")).isTrue();
        assertThat(rule.noSignal("hi there")).isTrue();
        assertThat(rule.noSignal("hi   there")).isTrue();
        assertThat(rule.noSignal("good morning")).isTrue();
    }

    @Test
    @DisplayName("英文寒暄也是整条精确匹配：夹带症状的句子一律放行")
    void englishGreetingsMatchWholeMessageOnly() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("hello，我胸口疼")).isFalse();
        assertThat(rule.noSignal("hi doctor, I have a headache")).isFalse();
    }

    @Test
    @DisplayName("英文应答不算无效：yes/no/ok/thanks 与中文的\"是的/没有/好的\"同理")
    void keepsEnglishAnswers() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("yes")).isFalse();
        assertThat(rule.noSignal("no")).isFalse();
        assertThat(rule.noSignal("ok")).isFalse();
        assertThat(rule.noSignal("thanks")).isFalse();
    }

    @Test
    @DisplayName("尾部标点不改变判定：'在吗？' '嗯。' 同样判无效")
    void ignoresTrailingPunctuation() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("在吗？")).isTrue();
        assertThat(rule.noSignal("嗯。")).isTrue();
        assertThat(rule.noSignal("你好！！！")).isTrue();
    }

    @Test
    @DisplayName("否定与确认应答不算无效：'没有' 在追问上下文里正是有效回答")
    void keepsNegationsAndConfirmations() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("没有")).isFalse();
        assertThat(rule.noSignal("没有发烧")).isFalse();
        assertThat(rule.noSignal("是的")).isFalse();
        assertThat(rule.noSignal("好的")).isFalse();
    }

    @Test
    @DisplayName("口语短回答不算无效：白名单未命中也不拦（应答轮放行给模型）")
    void keepsColloquialShortAnswers() {
        SufficiencyRule rule = rule();
        assertThat(rule.noSignal("三天了")).isFalse();
        assertThat(rule.noSignal("左边，按压更疼")).isFalse();
        assertThat(rule.noSignal("肚子疼")).isFalse();
        assertThat(rule.noSignal("还拉肚子")).isFalse();
        assertThat(rule.noSignal("38.5")).isFalse();
    }

    @Test
    @DisplayName("首条门槛：过短且未命中术语才拦，命中术语或字数够都放行")
    void firstTurnGate() {
        SufficiencyRule rule = rule();
        when(terms.matches("我肚子不舒服")).thenReturn(true);

        assertThat(rule.tooVague("我不舒服")).isTrue();
        assertThat(rule.tooVague("我肚子不舒服")).isFalse();
        assertThat(rule.tooVague("最近总是胸口发闷还有点喘不上气")).isFalse();
        assertThat(rule.tooVague(null)).isTrue();
    }

    @Test
    @DisplayName("模板追问话术取自 prompts.yml 配置（两档都在）")
    void templateQuestionFromConfig() {
        SufficiencyRule rule = rule();
        assertThat(rule.templateQuestion()).isEqualTo(QUESTION);
        assertThat(rule.templateQuestionRepeat()).isEqualTo(QUESTION_REPEAT);
    }
}
