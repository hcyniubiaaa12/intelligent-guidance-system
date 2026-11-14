package com.guide.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.HealthProfileAssembler;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.chat.dto.ChatDTO;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.SessionStatus;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.chat.support.RecommendableDepts;
import com.guide.common.model.ChunkHit;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.rag.dto.RagAnswer;
import com.guide.rag.dto.RagContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 证据快照 profile 节点（单据 04）写侧单测。
 *
 * <p>断言对象是**落库的 evidence JSON**：profile 节点要能还原"系统当时看到了什么"——
 * 待注入文本（模型看到的）+ 检索用串（检索用到的）+ 结构化档案（患者当时填的）三者齐全。
 *
 * <p>核心验收是「档案可变、导诊记录不可改」：快照在结论落库当刻定稿，写的就是**当时那一份**。
 * 两次导诊用不同档案，各自快照互不影响——第二份档案不会污染第一条记录（这正是"改档案回改历史"
 * 要防的事）。无档案 ⇒ 节点为 null，快照除多一个空节点外与今天逐字节一致。
 */
class GuideServiceTest {

    private GuideRecordMapper guideRecordMapper;
    private ChatSessionMapper sessionMapper;
    private DeptService deptService;
    private RecommendableDepts recommendableDepts;
    private GuideService service;

    @BeforeEach
    void setUp() {
        guideRecordMapper = mock(GuideRecordMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        deptService = mock(DeptService.class);
        recommendableDepts = mock(RecommendableDepts.class);
        SysConfigService sysConfigService = mock(SysConfigService.class);

        Dept dept = dept("d-cardio", "心血管内科");
        when(recommendableDepts.list()).thenReturn(List.of(dept));
        when(deptService.listEnabled()).thenReturn(List.of(dept));
        when(sysConfigService.getDouble(anyString(), anyDouble())).thenReturn(0.5);

        service = new GuideService(guideRecordMapper, sessionMapper, deptService,
                recommendableDepts, sysConfigService, new ObjectMapper());
    }

    @Test
    @DisplayName("profile 节点：待注入文本 + 结构化档案内容；档案退出检索后不再写检索用串")
    void writesProfileNodeWithoutRecallQuery() {
        HealthProfileAssembler.Profile structure = new HealthProfileAssembler.Profile(
                "male", 1980, "45-59",
                List.of("2型糖尿病"), null,
                List.of(new MedicationEntry("二甲双胍", "2024-03", null)), null,
                List.of("青霉素类"), "对海鲜过敏",
                List.of(new SurgeryEntry("阑尾切除术", "2024-05")), null);
        GuideService.ProfileSnapshot snapshot = new GuideService.ProfileSnapshot(
                "男、1980 年出生、过敏：青霉素类、对海鲜过敏、近期用药：二甲双胍（2024-03 至今）", structure);

        service.saveConclusion(session("s1"), answer(), context(), "raw-output", snapshot);

        JsonNode profile = capturedEvidence(1).path("profile");
        assertThat(profile.isObject()).isTrue();
        assertThat(profile.path("text").asText())
                .isEqualTo("男、1980 年出生、过敏：青霉素类、对海鲜过敏、近期用药：二甲双胍（2024-03 至今）");
        // 新快照不再写 profile.query：档案已退出检索，留一个检索用串只会诱导后人把它重新接回去。
        // 老快照里的 query 仍可安全读取（RecordService 只读 text，见 RecordServiceTest）。
        assertThat(profile.has("query")).isFalse();

        JsonNode content = profile.path("content");
        assertThat(content.path("gender").asText()).isEqualTo("male");
        assertThat(content.path("birthYear").asInt()).isEqualTo(1980);
        assertThat(content.path("ageRange").asText()).isEqualTo("45-59");
        assertThat(content.path("historyTags").get(0).asText()).isEqualTo("2型糖尿病");
        assertThat(content.path("allergyTags").get(0).asText()).isEqualTo("青霉素类");
        assertThat(content.path("allergyOther").asText()).isEqualTo("对海鲜过敏");
        assertThat(content.path("historyOther").isNull()).isTrue();
        // 用药/手术写对象数组（含时间）：审核回放要看得出"当时说的是近期还是已停/既往"
        JsonNode medication = content.path("medicationTags").get(0);
        assertThat(medication.path("value").asText()).isEqualTo("二甲双胍");
        assertThat(medication.path("startedOn").asText()).isEqualTo("2024-03");
        assertThat(medication.path("endedOn").isNull()).isTrue();
        JsonNode surgery = content.path("surgeryTags").get(0);
        assertThat(surgery.path("value").asText()).isEqualTo("阑尾切除术");
        assertThat(surgery.path("occurredOn").asText()).isEqualTo("2024-05");
    }

    @Test
    @DisplayName("无档案：profile 节点为 null（回归——快照除多一个空节点外与今天一致）")
    void emptyProfileYieldsNullNode() {
        service.saveConclusion(session("s1"), answer(), context(), "raw-output",
                new GuideService.ProfileSnapshot(null, null));

        JsonNode evidence = capturedEvidence(1);
        assertThat(evidence.has("profile")).isTrue();
        assertThat(evidence.path("profile").isNull()).isTrue();
    }

    @Test
    @DisplayName("档案快照是「当时那一份」：两次导诊用不同档案，各留各的（改档案不回改历史）")
    void eachRecordKeepsItsOwnProfile() {
        service.saveConclusion(session("s1"), answer(), context(), "raw-1",
                new GuideService.ProfileSnapshot("糖尿病史", structure("2型糖尿病")));
        service.saveConclusion(session("s2"), answer(), context(), "raw-2",
                new GuideService.ProfileSnapshot("高血压史", structure("高血压")));

        List<JsonNode> evidences = capturedEvidences(2);
        assertThat(evidences.get(0).path("profile").path("text").asText()).isEqualTo("糖尿病史");
        assertThat(evidences.get(1).path("profile").path("text").asText()).isEqualTo("高血压史");
        // 第二条改用别的档案后，第一条快照纹丝不动——这正是本单据要防的"回改历史"
        assertThat(evidences.get(0).path("profile").path("content").path("historyTags").get(0).asText())
                .isEqualTo("2型糖尿病");
        assertThat(evidences.get(1).path("profile").path("content").path("historyTags").get(0).asText())
                .isEqualTo("高血压");
    }

    @Test
    @DisplayName("推荐校验只接受可推荐科室：模型输出的零切片科室被过滤，可推荐科室保留")
    void zeroChunkDeptIsFiltered() {
        RagAnswer answer = new RagAnswer(RagAnswer.Verdict.RECOMMEND, "建议先到心血管内科。",
                List.of(new RagAnswer.DeptCandidate("老年医学科", 0.7),
                        new RagAnswer.DeptCandidate("心血管内科", 0.6)),
                "老年多病共存", List.of(), 0.7, true);

        service.saveConclusion(session("s1"), answer, context(), "raw-output",
                new GuideService.ProfileSnapshot(null, null));

        GuideRecord record = capturedRecord(1);
        assertThat(record.getRecDeptId()).isEqualTo("d-cardio");
        assertThat(record.getRecTop3()).doesNotContain("老年医学科").contains("心血管内科");
    }

    @Test
    @DisplayName("Top3 被滤光（全为零切片科室）⇒ 走既有兜底路径：回落检索片段所属科室、低置信度")
    void allFilteredFallsBackToChunkDept() {
        RagAnswer answer = new RagAnswer(RagAnswer.Verdict.RECOMMEND, "建议先到老年医学科。",
                List.of(new RagAnswer.DeptCandidate("老年医学科", 0.7)),
                "老年多病共存", List.of(), 0.7, true);
        when(deptService.getById("d-cardio")).thenReturn(dept("d-cardio", "心血管内科"));

        service.saveConclusion(session("s1"), answer, contextWithCardioChunk(), "raw-output",
                new GuideService.ProfileSnapshot(null, null));

        GuideRecord record = capturedRecord(1);
        assertThat(record.getRecDeptId()).isEqualTo("d-cardio");
        assertThat(record.getConfidence()).isNull();        // 兜底科室无置信度
        assertThat(record.getLowConfidence()).isEqualTo(1); // 分流进盲区榜
    }

    @Test
    @DisplayName("出卡即归零追问预算：ask_round 语义重定义为「距最近一张结论卡已追问几轮」（工单 05 ②）")
    void conclusionResetsAskRound() {
        ChatSession asked = session("s1");
        asked.setAskRound(3); // 常见路径：预算用满才拿到结论

        service.saveConclusion(asked, answer(), context(), "raw-output",
                new GuideService.ProfileSnapshot(null, null));

        // 同一个 updateById 里两个事实一起落：has_result=1 与 ask_round=0
        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getHasResult()).isEqualTo(1);
        assertThat(captor.getValue().getAskRound()).isZero();
        assertThat(asked.getAskRound()).isZero();
    }

    // —— 档案硬警报（工单 10）——

    @Test
    @DisplayName("规则警报随 result 下发，并原样写进证据快照 alerts 节点")
    void ruleAlertIsPublishedAndSnapshotted() {
        GuideService.ProfileSnapshot snapshot = new GuideService.ProfileSnapshot(
                "男、过敏：青霉素类", profileWithAllergy("青霉素类"));

        GuideService.Conclusion conclusion = service.saveConclusion(session("s1"), answer(),
                contextWith("青霉素皮试阳性者禁用阿莫西林。"), "raw-output", snapshot);

        List<ChatDTO.AlertVO> alerts = conclusion.payload().alerts();
        assertThat(alerts).hasSize(1);
        ChatDTO.AlertVO alert = alerts.get(0);
        assertThat(alert.level()).isEqualTo("hard");
        assertThat(alert.source()).isEqualTo("rule");
        assertThat(alert.anchor()).isEqualTo("field:allergy");
        assertThat(alert.text()).contains("过敏史").contains("青霉素类").contains("青霉素");

        JsonNode snapshotAlerts = capturedEvidence(1).path("alerts");
        assertThat(snapshotAlerts.isArray()).as("快照必须有 alerts 节点（空也写空数组）").isTrue();
        assertThat(snapshotAlerts.get(0).path("text").asText()).isEqualTo(alert.text());
        assertThat(snapshotAlerts.get(0).path("level").asText()).isEqualTo("hard");
        assertThat(snapshotAlerts.get(0).path("anchor").asText()).isEqualTo("field:allergy");
    }

    @Test
    @DisplayName("无冲突：结果体与快照都不出警报，前端不渲染空警示条")
    void noConflictYieldsEmptyAlerts() {
        GuideService.Conclusion conclusion = service.saveConclusion(session("s1"), answer(),
                contextWith("上腹部疼痛可考虑胃炎。"), "raw-output",
                new GuideService.ProfileSnapshot("过敏：青霉素类", profileWithAllergy("青霉素类")));

        assertThat(conclusion.payload().alerts()).isEmpty();
        JsonNode snapshotAlerts = capturedEvidence(1).path("alerts");
        assertThat(snapshotAlerts.isArray()).isTrue();
        assertThat(snapshotAlerts).isEmpty();
    }

    @Test
    @DisplayName("模型警报注号越界：丢弃（解析层看不到召回条数，范围校验在 chat 层）")
    void modelAlertWithOutOfRangeCiteIsDropped() {
        RagAnswer answer = answerWithAlerts(new RagAnswer.Alert("片段提示与过敏史冲突", 9, null));

        GuideService.Conclusion conclusion = service.saveConclusion(session("s1"), answer,
                contextWith("上腹部疼痛可考虑胃炎。"), "raw-output",
                new GuideService.ProfileSnapshot(null, null));

        assertThat(conclusion.payload().alerts()).isEmpty();
    }

    @Test
    @DisplayName("模型警报带档案字段锚点：保留并转成 field 锚点，来源标 model")
    void modelAlertWithFieldAnchorIsKept() {
        RagAnswer answer = answerWithAlerts(
                new RagAnswer.Alert("片段提到的手术与您的近期手术相关", null, "surgery"));

        GuideService.Conclusion conclusion = service.saveConclusion(session("s1"), answer,
                contextWith("上腹部疼痛可考虑胃炎。"), "raw-output",
                new GuideService.ProfileSnapshot(null, null));

        assertThat(conclusion.payload().alerts()).hasSize(1);
        ChatDTO.AlertVO alert = conclusion.payload().alerts().get(0);
        assertThat(alert.level()).isEqualTo("hard");
        assertThat(alert.source()).isEqualTo("model");
        assertThat(alert.anchor()).isEqualTo("field:surgery");
        assertThat(alert.text()).contains("近期手术");
    }

    @Test
    @DisplayName("合并：规则警报优先；模型与规则同文案去重；模型补充排后；总上限 3 条")
    void ruleAlertsFirstDedupedAndCapped() {
        HealthProfileAssembler.Profile profile = new HealthProfileAssembler.Profile(
                null, null, null, List.of(), null, List.of(), null,
                List.of("青霉素类", "磺胺类"), null, List.of(), null);
        RagAnswer answer = answerWithAlerts(
                // 与规则第 1 条逐字相同 ⇒ 必须去重，不能出现两条一样的警报
                new RagAnswer.Alert("与您的过敏史「青霉素类」相关：知识片段提到青霉素，请务必向医生说明过敏史。", 1, null),
                new RagAnswer.Alert("模型补充：片段还提到磺胺类", null, "allergy"));

        GuideService.Conclusion conclusion = service.saveConclusion(session("s1"), answer,
                contextWith("青霉素、磺胺都在这一段里。"), "raw-output",
                new GuideService.ProfileSnapshot("过敏：青霉素类、磺胺类", profile));

        List<ChatDTO.AlertVO> alerts = conclusion.payload().alerts();
        assertThat(alerts).hasSize(3);
        assertThat(alerts).extracting(ChatDTO.AlertVO::source)
                .as("两条规则在前，模型补充在后")
                .containsExactly("rule", "rule", "model");
        assertThat(alerts.get(0).text()).contains("青霉素类");
        assertThat(alerts.get(1).text()).contains("磺胺类");
        assertThat(alerts.get(2).text()).contains("模型补充");
    }

    // ---------------------------------------------------------------- 夹具

    private HealthProfileAssembler.Profile profileWithAllergy(String... allergyTags) {
        return new HealthProfileAssembler.Profile(
                null, null, null, List.of(), null,
                List.of(), null,
                List.of(allergyTags), null,
                List.of(), null);
    }

    private RagAnswer answerWithAlerts(RagAnswer.Alert... alerts) {
        return new RagAnswer(RagAnswer.Verdict.RECOMMEND, "建议先到心血管内科。",
                List.of(new RagAnswer.DeptCandidate("心血管内科", 0.82)),
                "结合既往史优先排查心血管来源", List.of(), 0.82, true, List.of(alerts));
    }

    private RagContext contextWith(String content) {
        return new RagContext("胸口闷", "胸口闷",
                List.of(new ChunkHit("c1", "d-cardio", "胸痛鉴别", content, 0.9)), 1, 1);
    }

    private HealthProfileAssembler.Profile structure(String historyTag) {
        return new HealthProfileAssembler.Profile(
                null, null, null,
                List.of(historyTag), null,
                List.of(), null,
                List.of(), null,
                List.of(), null);
    }

    private Dept dept(String id, String name) {
        Dept dept = new Dept();
        dept.setId(id);
        dept.setName(name);
        dept.setEnabled(1);
        return dept;
    }

    private GuideRecord capturedRecord(int inserts) {
        ArgumentCaptor<GuideRecord> captor = ArgumentCaptor.forClass(GuideRecord.class);
        verify(guideRecordMapper, times(inserts)).insert(captor.capture());
        return captor.getAllValues().get(inserts - 1);
    }

    private ChatSession session(String id) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId("u1");
        session.setStatus(SessionStatus.ONGOING);
        session.setAskRound(0);
        session.setHasResult(0);
        return session;
    }

    private RagAnswer answer() {
        return new RagAnswer(RagAnswer.Verdict.RECOMMEND, "建议先到心血管内科。",
                List.of(new RagAnswer.DeptCandidate("心血管内科", 0.82)),
                "结合既往史优先排查心血管来源", List.of(), 0.82, true);
    }

    private RagContext context() {
        return new RagContext("胸口闷", "胸口闷", List.of(), 0, 0);
    }

    /** 召回片段挂在心血管内科下：兜底选科据此回落 */
    private RagContext contextWithCardioChunk() {
        return new RagContext("胸口闷", "胸口闷",
                List.of(new ChunkHit("c1", "d-cardio", "胸痛鉴别", "活动后胸闷需排查心脏来源", 0.9)), 1, 1);
    }

    private JsonNode capturedEvidence(int inserts) {
        return capturedEvidences(inserts).get(inserts - 1);
    }

    private List<JsonNode> capturedEvidences(int inserts) {
        ArgumentCaptor<GuideRecord> captor = ArgumentCaptor.forClass(GuideRecord.class);
        verify(guideRecordMapper, times(inserts)).insert(captor.capture());
        return captor.getAllValues().stream().map(this::parse).toList();
    }

    private JsonNode parse(GuideRecord record) {
        try {
            return new ObjectMapper().readTree(record.getEvidence());
        } catch (Exception e) {
            throw new AssertionError("证据快照不是合法 JSON：" + record.getEvidence(), e);
        }
    }
}
