package com.guide.kb.split;

import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 模型切的 LLM 实现：经 llm 适配层调对话模型，**只给边界不给 title**。
 *
 * <p>提示词在 {@code prompts.yml} 的 {@code prompts.split.*}（与导诊主提示词同源）——
 * 切分质量直接就是检索质量（{@code chunk_broken} 是根因表第一项），迭代时不该改 Java 代码。
 *
 * <p>这里只负责"把模型的输出解析成片段列表"，**不做任何修补**：解析不出多个片段就原样返回单块，
 * 由 {@link DocSplitter} 的产出校验判它不合格。在这里替模型圆场，等于把"模型没切出来"
 * 这件事藏起来——而它会编一个看起来合理的切分，藏一次就会一直藏下去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmModelSplitter implements ModelSplitter {

    /**
     * 片段分隔符：**代码侧协议常量**（与结论分隔符同理），提示词用 {separator} 引用，
     * 避免两边各写一份而对不上。原文里偶然出现这个串也不会出错——拼回去仍与原文一致。
     */
    public static final String SEGMENT_SEPARATOR = "<<<SEG>>>";

    private final ChatModel chatModel;
    private final PromptProperties prompts;

    @Override
    public List<String> split(String text, SplitParams params) {
        String system = prompts.getSplit().getSystem()
                .replace(PromptProperties.PLACEHOLDER_SEPARATOR, SEGMENT_SEPARATOR)
                .replace(PromptProperties.PLACEHOLDER_TARGET, String.valueOf(params.targetLength()))
                .replace(PromptProperties.PLACEHOLDER_MAX, String.valueOf(params.maxLength()));
        String user = prompts.getSplit().getUserTemplate()
                .replace(PromptProperties.PLACEHOLDER_TEXT, text);

        long start = System.currentTimeMillis();
        String output = chatModel.chat(List.of(ChatMsg.system(system), ChatMsg.user(user)));
        List<String> segments = parse(output);
        log.info("模型切：原文 {} 字 → 模型给出 {} 段，耗时 {} ms",
                text.length(), segments.size(), System.currentTimeMillis() - start);
        return segments;
    }

    /** 去掉代码围栏后按分隔符切段；切不动就返回单块（产出校验会判它"未给出切分"） */
    private List<String> parse(String output) {
        String cleaned = stripFence(output == null ? "" : output.strip());
        List<String> segments = new ArrayList<>();
        for (String piece : cleaned.split(Pattern.quote(SEGMENT_SEPARATOR), -1)) {
            String segment = piece.strip();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /** 模型习惯把长文本包进 ``` 围栏——这是格式习惯，不是内容改动，剥掉不影响逐字校验 */
    private String stripFence(String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) {
            return text;
        }
        String inner = text.substring(3, text.length() - 3);
        int newline = inner.indexOf('\n');
        // 首行是语言标注（```text）时一并去掉
        return (newline >= 0 && !inner.substring(0, newline).contains(SEGMENT_SEPARATOR)
                ? inner.substring(newline + 1) : inner).strip();
    }
}
