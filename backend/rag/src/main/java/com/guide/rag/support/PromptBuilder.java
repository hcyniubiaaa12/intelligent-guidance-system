package com.guide.rag.support;

import com.guide.common.config.PromptProperties;
import com.guide.common.model.ChunkHit;
import com.guide.llm.client.ChatMsg;
import com.guide.rag.dto.DeptOption;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import com.guide.rag.dto.RagTurn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 拼装（链路 A 第 ④ 步末）：模板取自 prompts.yml（{@link PromptProperties}），
 * 此处只负责把「知识片段 / 候选科室 / 分隔符 / 轮次约束」填进占位符。
 * 知识片段带注号与精排分数（分数作为模型自报置信度的客观锚点），
 * 结论输出协议见 {@link AnswerParser}；判定与生成合并为一次 LLM 调用，零额外成本。
 */
@Component
public class PromptBuilder {

    private final PromptProperties prompts;

    public PromptBuilder(PromptProperties prompts) {
        this.prompts = prompts;
    }

    public List<ChatMsg> build(RagRequest request, RagContext context) {
        List<ChatMsg> messages = new ArrayList<>();
        messages.add(ChatMsg.system(systemPrompt(request, context)));
        for (RagTurn turn : request.history()) {
            messages.add(new ChatMsg(turn.role(), turn.content()));
        }
        messages.add(ChatMsg.user(request.query()));
        return messages;
    }

    private String systemPrompt(RagRequest request, RagContext context) {
        String prompt = PromptProperties.render(prompts.getDiagnosis().getSystemTemplate(),
                PromptProperties.PLACEHOLDER_KNOWLEDGE, knowledgeBlock(context));
        prompt = PromptProperties.render(prompt, PromptProperties.PLACEHOLDER_PROFILE, profileBlock(request));
        prompt = PromptProperties.render(prompt, PromptProperties.PLACEHOLDER_DEPTS, deptBlock(request));
        prompt = PromptProperties.render(prompt, PromptProperties.PLACEHOLDER_MARKER, AnswerParser.MARKER);
        prompt = PromptProperties.render(prompt, PromptProperties.PLACEHOLDER_EXTRA, extraBlock(request));
        return prompt.stripTrailing();
    }

    /** 知识片段：注号 + 精排相关度 + 标题 + 正文（注号即推荐卡溯源引用的注号） */
    private String knowledgeBlock(RagContext context) {
        if (context.isEmpty()) {
            return "（本次未检索到相关知识片段，请依据候选科室范围谨慎判断，并把置信度调低）";
        }
        StringBuilder sb = new StringBuilder();
        List<ChunkHit> chunks = context.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkHit chunk = chunks.get(i);
            sb.append("注").append(i + 1)
                    .append("（相关度 ").append(String.format("%.2f", chunk.score())).append("）")
                    .append(chunk.title() == null ? "" : "《" + chunk.title() + "》")
                    .append("：").append(chunk.content()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 患者健康档案（可选背景）：**标题 + 内容 + 前后换行作为一个整体块**参与占位符替换。
     *
     * <p>模板里 {@code {profile}} 紧贴 {@code {knowledge}}（{@code {knowledge}{profile}}），档案非空时
     * 本方法补上「空行 + 【患者健康档案】标题 + 换行」，渲染出与知识片段平行的一节；档案为空时返回空串，
     * 模板中那条空行与标题随之一并消失——**prompt 与不含档案占位符时逐字一致**（回归保证），
     * 不会留下"标题没了、空行还在"的连续空行。
     *
     * <p>档案是患者自述的背景，**不是医学证据**——硬约束统一写在主模板里，此处只出内容，
     * 不写指令性文字（避免两处口径漂移）。
     */
    private String profileBlock(RagRequest request) {
        String profileText = request.profileText();
        if (profileText == null || profileText.isBlank()) {
            return "";
        }
        return "\n\n【患者健康档案】\n" + profileText;
    }

    /**
     * 候选科室：模型只能从这里选。清单由 chat 层收敛为**可推荐科室**（启用且有切片）后传入，
     * 本层不感知业务状态；带简介则渲染「- 名称：简介」，**简介为空只渲染「- 名称」**（不出现孤零零的冒号）。
     */
    private String deptBlock(RagRequest request) {
        if (request.deptOptions().isEmpty()) {
            return "（无可用科室）";
        }
        StringBuilder sb = new StringBuilder();
        for (DeptOption dept : request.deptOptions()) {
            sb.append("- ").append(dept.name());
            if (dept.intro() != null && !dept.intro().isBlank()) {
                sb.append('：').append(dept.intro().strip());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 本轮附加约束：追问超限时强制出低置信度结论（verdict 也要相应给 RECOMMEND，否则会被当未声明降级） */
    private String extraBlock(RagRequest request) {
        if (!request.forceConclusion()) {
            return "";
        }
        return "【本轮约束】追问次数已达上限，本轮必须给出结论：即使信息仍不完整也要输出 "
                + AnswerParser.MARKER + " 与 verdict=RECOMMEND 的 JSON，并把置信度调低。";
    }
}
