package com.guide.admin;

import com.guide.common.config.PromptProperties;
import com.guide.common.model.ChunkHit;
import com.guide.llm.client.ChatMsg;
import com.guide.rag.dto.DeptOption;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import com.guide.rag.support.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档案段回归（真实 prompts.yml）：档案为空 ⇒ 档案一节连同标题与前后换行**整体不出现**，
 * prompt 不残留连续空行——与不含档案占位符时逐字一致。
 *
 * <p>放在 admin 模块：prompts.yml 在 admin 的类路径上，这里加载**真实模板**而非测试里另抄一份，
 * 避免模板改动后测试与实际配置漂移。PromptBuilder 在 rag、PromptProperties 在 common，admin 都能用到。
 */
class PromptProfileBlockTest {

    private PromptBuilder builder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        InputStream in = getClass().getClassLoader().getResourceAsStream("prompts.yml");
        Map<String, Object> root = new Yaml().load(in);
        Map<String, Object> prompts = (Map<String, Object>) root.get("prompts");
        Map<String, Object> diagnosis = (Map<String, Object>) prompts.get("diagnosis");
        PromptProperties properties = new PromptProperties();
        properties.getDiagnosis().setSystemTemplate((String) diagnosis.get("system-template"));
        builder = new PromptBuilder(properties);
    }

    private RagContext context() {
        return new RagContext("胸闷", "胸闷",
                List.of(new ChunkHit("c1", "d1", "胸痛鉴别", "活动后胸闷需排查心脏来源", 0.9)), 1, 1);
    }

    private RagRequest request(String profileText) {
        return new RagRequest("胸闷", List.of(),
                List.of(new DeptOption("d1", "心血管内科", "诊治心脏与血管疾病。")),
                0, false, 10, 3, profileText, null);
    }

    private String system(RagRequest request) {
        List<ChatMsg> messages = builder.build(request, context());
        return messages.get(0).content();
    }

    @Test
    @DisplayName("档案为空：无【患者健康档案】一节，且知识片段与候选科室之间只剩一个空行（无连续空行）")
    void emptyProfileLeavesNoProfileSectionNorBlankGap() {
        String prompt = system(request(null));

        assertThat(prompt).doesNotContain("【患者健康档案】");
        assertThat(prompt).doesNotContain("\n\n\n");
        assertThat(prompt).contains("活动后胸闷需排查心脏来源\n\n【候选科室】");
    }

    @Test
    @DisplayName("档案非空：出现【患者健康档案】一节，位于知识片段与候选科室之间")
    void nonEmptyProfileAppearsBetweenKnowledgeAndDepts() {
        String prompt = system(request("男、45-59岁、2型糖尿病"));

        assertThat(prompt).contains("\n\n【患者健康档案】\n男、45-59岁、2型糖尿病\n\n【候选科室】");
    }
}
