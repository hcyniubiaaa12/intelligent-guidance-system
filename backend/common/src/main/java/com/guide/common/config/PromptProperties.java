package com.guide.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 提示词配置（prompts.yml，随应用启动加载：见 application.yml 的 spring.config.import）。
 *
 * <p>提示词外置的理由：它是导诊质量的直接杠杆（改一句话就影响推荐与追问质量），
 * 迭代时不应该改 Java 代码、重新编译；同时让「输出协议」这类约定集中在一处可审。
 *
 * <p>模板占位符用 {xxx}，由各调用方替换；启动时校验必填项与占位符，配错立即失败而不是等第一次导诊。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {

    /** 结论分隔符占位符：实际值取自 AnswerParser.MARKER（协议由代码固定，提示词只引用） */
    public static final String PLACEHOLDER_MARKER = "{marker}";
    public static final String PLACEHOLDER_KNOWLEDGE = "{knowledge}";
    public static final String PLACEHOLDER_DEPTS = "{depts}";
    public static final String PLACEHOLDER_EXTRA = "{extra}";
    public static final String PLACEHOLDER_DIALOGUE = "{dialogue}";
    /** 禁言话术里的剩余分钟数 */
    public static final String PLACEHOLDER_MINUTES = "{minutes}";

    private Diagnosis diagnosis = new Diagnosis();

    private Rewrite rewrite = new Rewrite();

    private Chat chat = new Chat();

    /** 导诊主提示词（rag 检索层拼装） */
    @Data
    public static class Diagnosis {
        /** 必含 {knowledge} {depts} {marker} {extra} */
        private String systemTemplate;
    }

    /** 多轮查询改写提示词 */
    @Data
    public static class Rewrite {
        private String system;
        /** 必含 {dialogue} */
        private String userTemplate;
    }

    /** 对话链路话术（chat 层） */
    @Data
    public static class Chat {
        /** 规则硬门槛的模板追问（主诉连部位+症状都没有时） */
        private String templateQuestion;
        /** 敏感词拦截话术：固定引导，不道歉不模糊 */
        private String blockedReply;
        /** 敏感词累计触发的警告（不阻断本轮，另起一条气泡） */
        private String warnReply;
        /** 禁言提示：必含 {minutes} */
        private String muteReply;
    }

    @PostConstruct
    void validate() {
        require(diagnosis.getSystemTemplate(), "prompts.diagnosis.system-template",
                List.of(PLACEHOLDER_KNOWLEDGE, PLACEHOLDER_DEPTS, PLACEHOLDER_MARKER, PLACEHOLDER_EXTRA));
        require(rewrite.getSystem(), "prompts.rewrite.system", List.of());
        require(rewrite.getUserTemplate(), "prompts.rewrite.user-template", List.of(PLACEHOLDER_DIALOGUE));
        require(chat.getTemplateQuestion(), "prompts.chat.template-question", List.of());
        require(chat.getBlockedReply(), "prompts.chat.blocked-reply", List.of());
        require(chat.getWarnReply(), "prompts.chat.warn-reply", List.of());
        require(chat.getMuteReply(), "prompts.chat.mute-reply", List.of(PLACEHOLDER_MINUTES));
        log.info("提示词配置已加载：{}", "prompts.yml");
    }

    private void require(String template, String key, List<String> placeholders) {
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("提示词未配置：" + key + "（检查 resources/prompts.yml）");
        }
        for (String placeholder : placeholders) {
            if (!template.contains(placeholder)) {
                throw new IllegalStateException("提示词 " + key + " 缺少占位符 " + placeholder
                        + "——该占位符承载的内容（知识片段/科室范围/结论分隔符/轮次约束）会静默丢失");
            }
        }
    }

    /** 替换占位符（模板里的 JSON 花括号不含这些关键字，不会被误替换） */
    public static String render(String template, String placeholder, String value) {
        return template.replace(placeholder, value == null ? "" : value);
    }
}
