package com.guide.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.HealthProfileAssembler;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.SessionStatus;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.chat.mapper.GuideRecordMapper;
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
    private GuideService service;

    @BeforeEach
    void setUp() {
        guideRecordMapper = mock(GuideRecordMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        DeptService deptService = mock(DeptService.class);
        SysConfigService sysConfigService = mock(SysConfigService.class);

        Dept dept = new Dept();
        dept.setId("d-cardio");
        dept.setName("心血管内科");
        dept.setEnabled(1);
        when(deptService.listEnabled()).thenReturn(List.of(dept));
        when(sysConfigService.getDouble(anyString(), anyDouble())).thenReturn(0.5);

        service = new GuideService(guideRecordMapper, sessionMapper, deptService,
                sysConfigService, new ObjectMapper());
    }

    @Test
    @DisplayName("profile 节点三要素齐全：待注入文本 + 检索用串 + 结构化档案内容")
    void writesProfileNodeWithAllThree() {
        HealthProfileAssembler.Profile structure = new HealthProfileAssembler.Profile(
                "male", "45-59",
                List.of("2型糖尿病"), null,
                List.of(), null,
                List.of("青霉素类"), "对海鲜过敏");
        GuideService.ProfileSnapshot snapshot = new GuideService.ProfileSnapshot(
                "男、45-59岁、2型糖尿病、青霉素类、对海鲜过敏",
                "男、45-59岁、2型糖尿病、青霉素类", structure);

        service.saveConclusion(session("s1"), answer(), context(), "raw-output", snapshot);

        JsonNode profile = capturedEvidence(1).path("profile");
        assertThat(profile.isObject()).isTrue();
        assertThat(profile.path("text").asText()).isEqualTo("男、45-59岁、2型糖尿病、青霉素类、对海鲜过敏");
        assertThat(profile.path("query").asText()).isEqualTo("男、45-59岁、2型糖尿病、青霉素类");

        JsonNode content = profile.path("content");
        assertThat(content.path("gender").asText()).isEqualTo("male");
        assertThat(content.path("ageRange").asText()).isEqualTo("45-59");
        assertThat(content.path("historyTags").get(0).asText()).isEqualTo("2型糖尿病");
        assertThat(content.path("allergyTags").get(0).asText()).isEqualTo("青霉素类");
        assertThat(content.path("allergyOther").asText()).isEqualTo("对海鲜过敏");
        assertThat(content.path("historyOther").isNull()).isTrue();
        assertThat(content.path("medicationTags").isArray()).isTrue();
    }

    @Test
    @DisplayName("无档案：profile 节点为 null（回归——快照除多一个空节点外与今天一致）")
    void emptyProfileYieldsNullNode() {
        service.saveConclusion(session("s1"), answer(), context(), "raw-output",
                new GuideService.ProfileSnapshot(null, null, null));

        JsonNode evidence = capturedEvidence(1);
        assertThat(evidence.has("profile")).isTrue();
        assertThat(evidence.path("profile").isNull()).isTrue();
    }

    @Test
    @DisplayName("档案快照是「当时那一份」：两次导诊用不同档案，各留各的（改档案不回改历史）")
    void eachRecordKeepsItsOwnProfile() {
        service.saveConclusion(session("s1"), answer(), context(), "raw-1",
                new GuideService.ProfileSnapshot("糖尿病史", "糖尿病史", structure("2型糖尿病")));
        service.saveConclusion(session("s2"), answer(), context(), "raw-2",
                new GuideService.ProfileSnapshot("高血压史", "高血压史", structure("高血压")));

        List<JsonNode> evidences = capturedEvidences(2);
        assertThat(evidences.get(0).path("profile").path("text").asText()).isEqualTo("糖尿病史");
        assertThat(evidences.get(1).path("profile").path("text").asText()).isEqualTo("高血压史");
        // 第二条改用别的档案后，第一条快照纹丝不动——这正是本单据要防的"回改历史"
        assertThat(evidences.get(0).path("profile").path("content").path("historyTags").get(0).asText())
                .isEqualTo("2型糖尿病");
        assertThat(evidences.get(1).path("profile").path("content").path("historyTags").get(0).asText())
                .isEqualTo("高血压");
    }

    // ---------------------------------------------------------------- 夹具

    private HealthProfileAssembler.Profile structure(String historyTag) {
        return new HealthProfileAssembler.Profile(
                null, null, List.of(historyTag), null, List.of(), null, List.of(), null);
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
