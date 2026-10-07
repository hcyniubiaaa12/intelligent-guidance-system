package com.guide.rag.support;

import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatMsg;
import com.guide.rag.dto.DeptOption;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选科室块渲染（{@code PromptBuilder.deptBlock}）：带简介渲染「- 名称：简介」，
 * 简介为空/空白只渲染「- 名称」——**不出现孤零零的冒号**。
 *
 * <p>纯逻辑单测：手工构造 {@link PromptProperties}（不加载真实 prompts.yml，避免耦合模板其余部分），
 * 只盯候选科室这一段。简介由 chat 组装后经 {@link DeptOption} 传入，rag 不查任何表。
 */
class PromptBuilderTest {

    private PromptBuilder builder;

    @BeforeEach
    void setUp() {
        PromptProperties properties = new PromptProperties();
        properties.getDiagnosis().setSystemTemplate(
                "知识：{knowledge}{profile}\n科室：\n{depts}\n尾{marker}{extra}");
        builder = new PromptBuilder(properties);
    }

    @Test
    @DisplayName("候选科室带简介：渲染成「- 科室名：简介」")
    void deptBlockRendersIntro() {
        String prompt = system(List.of(new DeptOption("d1", "心血管内科", "诊治冠心病、高血压等。")));

        assertThat(prompt).contains("- 心血管内科：诊治冠心病、高血压等。");
    }

    @Test
    @DisplayName("简介为空：只渲染「- 科室名」，不出现多余冒号")
    void blankIntroRendersNameOnly() {
        String prompt = system(List.of(new DeptOption("d2", "老年医学科", "   ")));

        assertThat(prompt).contains("- 老年医学科");
        assertThat(prompt).doesNotContain("老年医学科：");
    }

    @Test
    @DisplayName("简介为 null：同样只渲染「- 科室名」")
    void nullIntroRendersNameOnly() {
        String prompt = system(List.of(new DeptOption("d3", "妇科", null)));

        assertThat(prompt).contains("- 妇科");
        assertThat(prompt).doesNotContain("妇科：");
    }

    @Test
    @DisplayName("无候选科室：渲染「（无可用科室）」")
    void emptyDeptsRenderPlaceholder() {
        String prompt = system(List.of());

        assertThat(prompt).contains("（无可用科室）");
    }

    private String system(List<DeptOption> depts) {
        RagRequest request = new RagRequest("胸闷", List.of(), depts, 0, false, 10, 3, null, null, List.of());
        RagContext context = new RagContext("胸闷", "胸闷", List.of(), 0, 0);
        List<ChatMsg> messages = builder.build(request, context);
        return messages.get(0).content();
    }
}
