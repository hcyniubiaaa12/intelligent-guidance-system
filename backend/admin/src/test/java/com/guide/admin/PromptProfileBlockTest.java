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
 * 可选段落的回归（真实 prompts.yml）：档案 / 已标部位为空 ⇒ 对应一节连同标题与前后换行
 * **整体不出现**，prompt 不残留连续空行。
 *
 * <p>放在 admin 模块：prompts.yml 在 admin 的类路径上，这里加载**真实模板**而非测试里另抄一份，
 * 避免模板改动后测试与实际配置漂移。PromptBuilder 在 rag、PromptProperties 在 common，admin 都能用到。
 *
 * <p>「消不消失」的只有<b>内容节</b>（【患者健康档案】/【患者标明的位置】里填患者资料的那几行）；
 * 讲规矩的那几节（【硬约束】/【已标位置】）是模板里的静态文字、始终在——与档案那条完全同构：
 * 规矩常驻、资料按需，理由是规矩要写在模板里（改提示词不改代码），资料由代码按有无渲染。
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
        return request(profileText, List.of());
    }

    private RagRequest request(String profileText, List<String> parts) {
        return new RagRequest("胸闷", List.of(),
                List.of(new DeptOption("d1", "心血管内科", "诊治心脏与血管疾病。")),
                0, false, 10, 3, profileText, null, parts);
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

    @Test
    @DisplayName("无部位声明：无【患者标明的位置】内容节，且不留连续空行")
    void emptyPartsLeavesNoSectionNorBlankGap() {
        String prompt = system(request(null, List.of()));

        // 断言的是**成节形态**（前后换行 + 标题独占一行），不是"这个字符串出现过没有"：
        // 【已标位置】那节静态文字里本来就提到「【患者标明的位置】」这个名字，
        // 拿裸字符串去doesNotContain 会把一句说明误判成内容节——曾经就这么写错了。
        assertThat(prompt).doesNotContain("\n\n【患者标明的位置】\n");
        assertThat(prompt).doesNotContain("\n\n\n");
    }

    @Test
    @DisplayName("有部位声明：出现【患者标明的位置】一节，多个部位顿号连接")
    void declaredPartsAppearAsOneSection() {
        String prompt = system(request(null, List.of("左腹部", "腰")));

        assertThat(prompt).contains("\n\n【患者标明的位置】\n左腹部、腰\n\n【候选科室】");
    }

    @Test
    @DisplayName("档案与部位同时存在：两节各自成节，顺序是知识片段 → 档案 → 部位（不互相吞掉）")
    void profileAndPartsCoexist() {
        String prompt = system(request("男、45-59岁", List.of("左腹部")));

        assertThat(prompt).contains("\n\n【患者健康档案】\n男、45-59岁\n\n【患者标明的位置】\n左腹部\n\n【候选科室】");
    }

    @Test
    @DisplayName("提示词里写着「绝不追问部位」且部位已豁免（不然省不下这一轮追问）")
    void templateForbidsReaskingLocation() {
        String prompt = system(request(null, List.of("左腹部")));

        // 硬约束 3 原本写「部位……一律仍须追问」，与本功能直接冲突；枚举的两处都必须改
        assertThat(prompt).contains("绝不可再追问部位");
        assertThat(prompt).doesNotContain("一律仍须追问");
        // 部位不进注号体系、不决定科室——两条边界同样要写死在提示词里
        assertThat(prompt).contains("不写进 cites");
    }
}
