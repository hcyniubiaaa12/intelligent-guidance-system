package com.guide.rag.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.rag.dto.RagAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型输出解析单测：verdict 三态（RECOMMEND / ASK / INFO）、未声明降级、畸形输出容错。
 *
 * <p>「未声明」这一组是 2026-09-30 的关键行为变更：无分隔符 / JSON 畸形 / 声明了结论却没给 dept，
 * 此前全都返回 ASK（等于把模型没按协议来伪装成正常追问），现在一律 UNKNOWN。
 */
class AnswerParserTest {

    private final AnswerParser parser = new AnswerParser(new ObjectMapper());

    @Test
    @DisplayName("正常结论：自然语言 + 分隔符 + JSON")
    void parsesRecommend() {
        String output = """
                活动后胸闷加重、休息可缓解，结合片段提示优先排查心脏来源。注1 支持心血管内科，
                注2 提到的呼吸系统鉴别暂不吻合。
                ---RESULT---
                {"dept":"心血管内科","confidence":0.82,"top3":[{"dept":"心血管内科","confidence":0.82},{"dept":"呼吸内科","confidence":0.11}],"note":"优先排查心脏来源","cites":[1,2]}
                """;

        RagAnswer answer = parser.parse(output);

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.RECOMMEND);
        assertThat(answer.reply()).contains("活动后胸闷加重").doesNotContain(AnswerParser.MARKER);
        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::dept).containsExactly("心血管内科", "呼吸内科");
        assertThat(answer.confidence()).isEqualTo(0.82);
        assertThat(answer.confidenceValid()).isTrue();
        assertThat(answer.cites()).containsExactly(1, 2);
        assertThat(answer.note()).isEqualTo("优先排查心脏来源");
    }

    @Test
    @DisplayName("模型声明 ASK：按追问解析（判据是声明，不是「有没有 JSON」）")
    void parsesDeclaredAsk() {
        RagAnswer answer = parser.parse("这个胸闷大概持续多久了？有没有向左肩放射？\n"
                + AnswerParser.MARKER + "\n{\"verdict\":\"ASK\"}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.ASK);
        assertThat(answer.reply()).isEqualTo("这个胸闷大概持续多久了？有没有向左肩放射？");
        assertThat(answer.top3()).isEmpty();
    }

    @Test
    @DisplayName("模型声明 INFO：按资料回答解析（不推荐科室、不占追问轮次）")
    void parsesDeclaredInfo() {
        RagAnswer answer = parser.parse("根据知识片段，P1 事故必须记录根因、影响用户数、恢复时间与补偿方案。\n"
                + AnswerParser.MARKER + "\n{\"verdict\":\"INFO\"}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.INFO);
        assertThat(answer.reply()).contains("P1 事故必须记录");
        assertThat(answer.top3()).isEmpty();
        assertThat(answer.confidence()).isNull();
    }

    @Test
    @DisplayName("verdict 大小写不敏感：ask 也能识别")
    void verdictIsCaseInsensitive() {
        assertThat(parser.parse(AnswerParser.MARKER + "\n{\"verdict\":\"ask\"}").verdict())
                .isEqualTo(RagAnswer.Verdict.ASK);
        assertThat(parser.parse(AnswerParser.MARKER + "\n{\"verdict\":\" info \"}").verdict())
                .isEqualTo(RagAnswer.Verdict.INFO);
    }

    @Test
    @DisplayName("verdict 取值不认识：按未声明处理，不当成追问")
    void unknownVerdictValueFallsBackToUnknown() {
        RagAnswer answer = parser.parse("这段是回答。\n" + AnswerParser.MARKER + "\n{\"verdict\":\"CHITCHAT\"}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.UNKNOWN);
        assertThat(answer.reply()).isEqualTo("这段是回答。");
    }

    @Test
    @DisplayName("声明 RECOMMEND 却没给 dept：按未声明处理（不能出结论，也不能退回追问）")
    void recommendWithoutDeptFallsBackToUnknown() {
        RagAnswer answer = parser.parse("建议就诊。\n" + AnswerParser.MARKER
                + "\n{\"verdict\":\"RECOMMEND\",\"confidence\":0.8}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.UNKNOWN);
        assertThat(answer.reply()).isEqualTo("建议就诊。");
    }

    @Test
    @DisplayName("无 verdict 但有 dept：老格式向后兼容，仍按结论")
    void legacyJsonWithoutVerdictStillRecommend() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"confidence\":0.6}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.RECOMMEND);
    }

    @Test
    @DisplayName("空输出：按未声明处理（不是追问，也不是结论）")
    void blankOutputFallsBackToUnknown() {
        RagAnswer answer = parser.parse("   ");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.UNKNOWN);
        assertThat(answer.reply()).isEmpty();
    }

    @Test
    @DisplayName("无分隔符且无 JSON：按未声明处理（不再等同于追问）")
    void treatAsUnknownWhenNoMarker() {
        RagAnswer answer = parser.parse("这个胸闷大概持续多久了？有没有向左肩放射？");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.UNKNOWN);
        assertThat(answer.reply()).isEqualTo("这个胸闷大概持续多久了？有没有向左肩放射？");
        assertThat(answer.top3()).isEmpty();
    }

    @Test
    @DisplayName("分隔符后 JSON 畸形：按未声明兜底，不抛异常")
    void malformedJsonFallsBackToUnknown() {
        RagAnswer answer = parser.parse("建议就诊。\n" + AnswerParser.MARKER + "\n{dept: 心血管内科");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.UNKNOWN);
        assertThat(answer.reply()).isEqualTo("建议就诊。");
    }

    @Test
    @DisplayName("置信度越界：标记为不可信，由上层置 null 走低置信度分流")
    void invalidConfidenceMarked() {
        RagAnswer tooHigh = parser.parse(AnswerParser.MARKER + "\n{\"dept\":\"骨科\",\"confidence\":1.5,\"top3\":[{\"dept\":\"骨科\",\"confidence\":1.5}]}");
        RagAnswer negative = parser.parse(AnswerParser.MARKER + "\n{\"dept\":\"骨科\",\"confidence\":-0.2}");
        RagAnswer missing = parser.parse(AnswerParser.MARKER + "\n{\"dept\":\"骨科\"}");

        assertThat(tooHigh.confidenceValid()).isFalse();
        assertThat(negative.confidenceValid()).isFalse();
        assertThat(missing.confidenceValid()).isFalse();
    }

    @Test
    @DisplayName("Top3 置信度非递减：整体判为不可信")
    void nonMonotonicTop3Marked() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"confidence\":0.5,\"top3\":[{\"dept\":\"骨科\",\"confidence\":0.5},{\"dept\":\"神经内科\",\"confidence\":0.8}]}");

        assertThat(answer.confidenceValid()).isFalse();
        assertThat(answer.top3()).hasSize(2);
    }

    @Test
    @DisplayName("容错：```json 围栏、JSON 后多余文字、cites 对象形态")
    void toleratesFormatNoise() {
        RagAnswer fenced = parser.parse("说明。\n" + AnswerParser.MARKER
                + "\n```json\n{\"dept\":\"消化内科\",\"confidence\":0.7,\"cites\":[{\"no\":1}]}\n```\n以上供参考。");
        RagAnswer noisy = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"消化内科\",\"confidence\":0.7,\"top3\":[{\"dept\":\"消化内科\",\"confidence\":0.7}]} 请以医生面诊为准。");

        assertThat(fenced.verdict()).isEqualTo(RagAnswer.Verdict.RECOMMEND);
        assertThat(fenced.cites()).containsExactly(1);
        assertThat(noisy.verdict()).isEqualTo(RagAnswer.Verdict.RECOMMEND);
        assertThat(noisy.top3()).hasSize(1);
    }

    @Test
    @DisplayName("只给 JSON 没给自然语言：仍按结论处理，reply 为空")
    void jsonOnlyStillRecommend() {
        RagAnswer answer = parser.parse("{\"dept\":\"皮肤科\",\"confidence\":0.6}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.RECOMMEND);
        assertThat(answer.reply()).isEmpty();
    }

    @Test
    @DisplayName("top3 超过 3 条时截断为 3 条")
    void truncatesTop3() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER + """
                {"dept":"骨科","confidence":0.6,"top3":[{"dept":"骨科","confidence":0.6},{"dept":"神经内科","confidence":0.3},{"dept":"风湿免疫科","confidence":0.2},{"dept":"康复科","confidence":0.1}]}""");

        assertThat(answer.top3()).hasSize(3);
    }

    // —— Top3 归一化（2026-10-02）——
    // 此前直接照抄模型给的数组顺序，模型不按提示词递减输出时 top1 会落到末尾，
    // 且顶层 confidence 会被 set(0) 安到数组第 0 条而非顶层 dept 上

    @Test
    @DisplayName("归一化①：模型把候选乱序给出时，按置信度重排，top1 不再落末尾")
    void sortsTop3ByConfidence() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"心血管内科\",\"confidence\":0.82,\"top3\":["
                + "{\"dept\":\"呼吸内科\",\"confidence\":0.11},"
                + "{\"dept\":\"心血管内科\",\"confidence\":0.82},"
                + "{\"dept\":\"消化内科\",\"confidence\":0.07}]}");

        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::dept)
                .containsExactly("心血管内科", "呼吸内科", "消化内科");
        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::confidence)
                .containsExactly(0.82, 0.11, 0.07);
    }

    @Test
    @DisplayName("归一化②：顶层 confidence 只落在顶层 dept 上，不被安到数组第 0 条")
    void topConfidenceStaysOnPrimaryDept() {
        // 模型把呼吸内科放在候选第 0 位、顶层 dept 却是心血管内科：
        // 顶层 confidence=0.82 必须属于心血管内科，不能被写到呼吸内科头上
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"心血管内科\",\"confidence\":0.82,\"top3\":["
                + "{\"dept\":\"呼吸内科\",\"confidence\":0.11}]}");

        assertThat(answer.top3().get(0).dept()).isEqualTo("心血管内科");
        assertThat(answer.top3().get(0).confidence()).isEqualTo(0.82);
        assertThat(answer.top3().get(1).dept()).isEqualTo("呼吸内科");
        assertThat(answer.top3().get(1).confidence())
                .as("呼吸内科保留自己的 0.11，不被顶层的 0.82 覆盖")
                .isEqualTo(0.11);
        assertThat(answer.confidence()).isEqualTo(0.82);
    }

    @Test
    @DisplayName("归一化③：顶层 dept 没进候选数组时补到首位")
    void primaryDeptMissingFromTop3GetsAdded() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"心血管内科\",\"confidence\":0.82,\"top3\":["
                + "{\"dept\":\"呼吸内科\",\"confidence\":0.11}]}");

        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::dept)
                .containsExactly("心血管内科", "呼吸内科");
    }

    @Test
    @DisplayName("归一化④：候选里同一科室写两遍时去重")
    void dedupesSameDept() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"confidence\":0.6,\"top3\":["
                + "{\"dept\":\"神经内科\",\"confidence\":0.3},"
                + "{\"dept\":\"骨科\",\"confidence\":0.6},"
                + "{\"dept\":\"神经内科\",\"confidence\":0.25}]}");

        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::dept)
                .containsExactly("骨科", "神经内科");
    }

    @Test
    @DisplayName("归一化⑤：置信度为 null 的候选排最后，不占 top1 位")
    void nullConfidenceSinksToLast() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"confidence\":0.6,\"top3\":["
                + "{\"dept\":\"神经内科\",\"confidence\":null},"
                + "{\"dept\":\"骨科\",\"confidence\":0.6},"
                + "{\"dept\":\"康复科\",\"confidence\":0.2}]}");

        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::dept)
                .containsExactly("骨科", "康复科", "神经内科");
        assertThat(answer.confidenceValid())
                .as("含 null 置信度仍整体判为不可信，走低置信度分流")
                .isFalse();
    }

    @Test
    @DisplayName("归一化⑥：顶层没给 confidence 时回落用候选里自带的那条")
    void fallsBackToCandidateConfidence() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"top3\":[{\"dept\":\"骨科\",\"confidence\":0.6},"
                + "{\"dept\":\"神经内科\",\"confidence\":0.3}]}");

        assertThat(answer.confidence()).isEqualTo(0.6);
        assertThat(answer.top3().get(0).confidence()).isEqualTo(0.6);
        assertThat(answer.confidenceValid()).isTrue();
    }

    // —— 模型补充警报（工单 10）——
    // 锚点是硬门槛：没有注号、也没有白名单档案字段的警报一律丢弃，
    // 否则模型可以凭空给患者加一条无处可查的"警告"

    @Test
    @DisplayName("模型警报带 cite 注号：保留")
    void keepsModelAlertWithCite() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"verdict\":\"RECOMMEND\",\"dept\":\"心血管内科\",\"confidence\":0.82,"
                + "\"alerts\":[{\"text\":\"片段提到的阿莫西林与您的青霉素过敏可能交叉过敏\",\"cite\":1}]}");

        assertThat(answer.alerts()).hasSize(1);
        assertThat(answer.alerts().get(0).text()).contains("青霉素");
        assertThat(answer.alerts().get(0).cite()).isEqualTo(1);
        assertThat(answer.alerts().get(0).field()).isNull();
    }

    @Test
    @DisplayName("模型警报带白名单档案字段：保留（field 锚点）")
    void keepsModelAlertWithProfileField() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"verdict\":\"RECOMMEND\",\"dept\":\"骨科\",\"confidence\":0.7,"
                + "\"alerts\":[{\"text\":\"与您的近期手术相关\",\"field\":\"surgery\"}]}");

        assertThat(answer.alerts()).hasSize(1);
        assertThat(answer.alerts().get(0).field()).isEqualTo("surgery");
        assertThat(answer.alerts().get(0).cite()).isNull();
    }

    @Test
    @DisplayName("模型警报无锚点：丢弃；field 不在白名单也丢弃")
    void dropsModelAlertWithoutUsableAnchor() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"verdict\":\"RECOMMEND\",\"dept\":\"骨科\",\"confidence\":0.7,"
                + "\"alerts\":[{\"text\":\"请注意一下\"},{\"text\":\"与既往病史相关\",\"field\":\"history\"}]}");

        assertThat(answer.alerts()).isEmpty();
    }

    @Test
    @DisplayName("模型警报文案为空：丢弃（锚点合法也不保留一条空警报）")
    void dropsBlankModelAlertText() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"verdict\":\"RECOMMEND\",\"dept\":\"骨科\",\"confidence\":0.7,"
                + "\"alerts\":[{\"text\":\"   \",\"cite\":1}]}");

        assertThat(answer.alerts()).isEmpty();
    }

    @Test
    @DisplayName("老输出没有 alerts 字段：不报错，警报为空")
    void legacyOutputWithoutAlertsIsFine() {
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"confidence\":0.6}");

        assertThat(answer.verdict()).isEqualTo(RagAnswer.Verdict.RECOMMEND);
        assertThat(answer.alerts()).isEmpty();
    }

    @Test
    @DisplayName("非结论轮（ASK / INFO）不携带结构化警报：即使模型写了 alerts 也丢弃")
    void nonConclusionRoundsNeverCarryAlerts() {
        RagAnswer ask = parser.parse("持续多久了？\n" + AnswerParser.MARKER
                + "\n{\"verdict\":\"ASK\",\"alerts\":[{\"text\":\"与过敏史相关\",\"field\":\"allergy\"}]}");
        RagAnswer info = parser.parse("根据片段……\n" + AnswerParser.MARKER
                + "\n{\"verdict\":\"INFO\",\"alerts\":[{\"text\":\"与过敏史相关\",\"field\":\"allergy\"}]}");

        assertThat(ask.alerts()).isEmpty();
        assertThat(info.alerts()).isEmpty();
    }

    @Test
    @DisplayName("归一化⑦：归一化后顺序合法，原非递减的输出不再被误判为不可信")
    void normalizationRepairsNonMonotonicOrder() {
        // 模型乱序给出（神经内科 0.8 排在前面），归一化后首位仍是顶层 dept，顺序变为合法递减
        RagAnswer answer = parser.parse(AnswerParser.MARKER
                + "\n{\"dept\":\"骨科\",\"confidence\":0.8,\"top3\":["
                + "{\"dept\":\"神经内科\",\"confidence\":0.7},"
                + "{\"dept\":\"骨科\",\"confidence\":0.8}]}");

        assertThat(answer.top3()).extracting(RagAnswer.DeptCandidate::dept)
                .containsExactly("骨科", "神经内科");
        assertThat(answer.confidenceValid()).isTrue();
    }
}
