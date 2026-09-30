package com.guide.chat.service;

import com.guide.common.config.PromptProperties;
import com.guide.kb.service.MedicalTermService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 信息充足性判定的第一层·规则硬门槛（链路 A 配套）：命中即模板追问，不调模型、不检索。
 *
 * <p>两类拦法：
 * <ol>
 *   <li><b>确定性无效输入</b>（{@link #noSignal}）：语气词、寒暄（中英文）、说不出、纯符号。**不区分轮次**——
 *       这类输入在任何上下文下都不含分诊信息，拦掉省一次模型调用。</li>
 *   <li><b>首条主诉过于笼统</b>（{@link #tooVague}）：过短且未命中术语（如"我不舒服"）。
 *       只有自由陈述才要求它自带"部位 + 症状"。</li>
 * </ol>
 *
 * <p><b>应答轮（追问之后）刻意不叠加内容门槛</b>：追问的回答天然是短句、且大量用口语
 * （"三天了""左边，按压更疼""还拉肚子""没有发烧"），而术语白名单是书面词形
 * （没有"疼/肚子/发烧/拉肚子"这类别名），拿"未命中术语"当闸门会系统性误杀有效回答。
 * 应答轮的语义判断交给持有完整历史的第二层（判定与生成合并为一次 LLM 调用，由 PromptBuilder 承担）。
 */
@Service
@RequiredArgsConstructor
public class SufficiencyRule {

    /** 过短阈值：低于此长度且不含任何医学术语，判为信息不足（仅首条主诉） */
    private static final int MIN_CHARS = 6;

    /**
     * 确定性无效输入词表：整条消息（去尾部标点、**大小写与空白归一**后）**精确等于**其中一项时判为无效。
     *
     * <p>必须整条精确匹配、不能子串——否则"没有发烧""不知道怎么说"这类有效回答会被误杀。
     * 也刻意不收"没有/是的/对/好的/行"等否定与确认应答：它们在追问上下文里正是有效回答。
     *
     * <p>英文只收**呼叫与问候**（hello/hi/hey/good morning…），理由与中文那批完全一样：
     * 患者可能先用一句招呼开场、还没说症状。同样**不收 yes/no/ok/thanks**——对应中文的
     * "是的/没有/好的"，是应答不是寒暄，按既有约定一律放行交给模型。
     */
    private static final Set<String> NOISE_WORDS = Set.of(
            // 语气词 / 无实义应答
            "嗯", "嗯嗯", "嗯哪", "哦", "哦哦", "噢", "啊", "呃", "额", "诶", "唉", "哈", "呀", "哎",
            // 寒暄 / 呼叫（还没说症状）
            "你好", "您好", "在吗", "在么", "在不在", "有人吗", "你好在吗",
            "hello", "hello there", "hi", "hi there", "hiya", "hey", "hey there", "yo",
            "good morning", "good afternoon", "good evening",
            // 明确表示说不出
            "不知道", "不知道啊", "不清楚", "不明白", "不晓得", "说不好", "我也说不清",
            "随便", "都行", "都可以", "你看着办", "你说呢");

    /** 尾部标点/符号/空白：判无效前先剥掉，避免"在吗？"这类变体漏判 */
    private static final Pattern TRAILING_NOISE = Pattern.compile("[\\p{P}\\p{S}\\s]+$");

    /** 连续空白折叠："hi   there" 与 "hi there" 应当同判（英文短语有空格，中文没有） */
    private static final Pattern INNER_SPACES = Pattern.compile("\\s+");

    private final MedicalTermService medicalTermService;
    private final PromptProperties prompts;

    /**
     * 是否为确定性无效输入（不区分轮次）：
     * 整条命中无效词表（大小写与空白归一后），或整条不含任何文字与数字（纯标点/表情/空白）。
     */
    public boolean noSignal(String content) {
        if (content == null) {
            return true;
        }
        String text = normalize(content);
        if (text.isEmpty() || NOISE_WORDS.contains(text)) {
            return true;
        }
        // 只含数字的（如"38.5"）不算无效——体温这类答案是有效信息，故用 isLetterOrDigit
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    /**
     * 归一：剥掉尾部标点符号 → 折叠内部连续空白 → 转小写。
     * 转小写是为了让 "Hello"/"HI!" 与词表里的 "hello"/"hi" 同判（中文不受影响）；
     * 折叠空白是因为英文短语带空格，"hi   there" 不该因为多打了个空格就漏判。
     */
    private String normalize(String content) {
        String text = TRAILING_NOISE.matcher(content).replaceAll("").trim();
        return INNER_SPACES.matcher(text).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** 是否为过于笼统的首条主诉：过短且未命中术语 */
    public boolean tooVague(String content) {
        if (content == null) {
            return true;
        }
        String text = content.trim();
        return text.length() < MIN_CHARS && !medicalTermService.matches(text);
    }

    /** 模板追问话术：来自 prompts.yml 的 prompts.chat.template-question */
    public String templateQuestion() {
        return prompts.getChat().getTemplateQuestion();
    }

    /** 第二档模板追问（本会话已回过一次模板追问后改用）：来自 prompts.chat.template-question-repeat */
    public String templateQuestionRepeat() {
        return prompts.getChat().getTemplateQuestionRepeat();
    }
}
