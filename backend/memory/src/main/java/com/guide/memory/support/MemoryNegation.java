package com.guide.memory.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 记忆的「提到 / 否定 / 更正」判定（召回与抽取共用一份口径）。
 *
 * <p>为什么单独抽出来：召回侧要判断「当前这句是否否定了这条背景」，
 * 抽取侧要判断「患者是不是在收回之前说过的背景」——两边判反了，一个会拿被否认的背景作答，
 * 另一个会让错误记忆一直留着。规则必须只有一份。
 *
 * <p><b>提到</b>不只是完整子串：患者说「糖尿病」，记忆值是「2型糖尿病」时，
 * 完整子串匹配不到，但这句话显然在说同一件事。因此完整子串没有命中时，
 * 取两者的**最长公共子串**（短值 ≥2 字、长值 ≥3 字，且必须含汉字/字母）作为命中。
 *
 * <p><b>否定</b>只认提到位置附近 12 字的否定词；「是不是 / 有没有 / 有无」这类疑问
 * 先等长替换成空格（保持下标），避免把「是不是和我的糖尿病有关」误判为否定。
 * 「可能 / 不确定」等不确定表述也不当否定；「家族史 / 并发症」等外部语境同样不算——
 * 患者说「我没有糖尿病家族史」不是否认自己患病。
 */
public final class MemoryNegation {

    /** 否定词：出现在背景值之前（或与值重叠）即认为当前这句否定了它 */
    private static final List<String> NEGATIONS = List.of(
            "没有", "不是", "不过敏", "没做过", "没吃", "未", "没");
    /** 疑问词：先抹掉再判否定，否则「是不是」「有没有」里的「不是 / 没有」会误伤 */
    private static final List<String> INTERROGATIVES = List.of("是不是", "有没有", "有无");
    /**
     * 收回记忆用的强否定：只认"直接否认这个情况"的词形。
     * 「我没吃糖尿病药」「我没做过糖尿病检查」是在说行动，不是否认患病——靠下面的动词排除挡住。
     */
    private static final List<String> RETRACTION_NEGATIONS = List.of(
            "没有", "不是", "并非", "未患", "没患", "未得", "没得");
    /** 否定词后面紧跟这些动词时，说的是"没做这件事"，不等于"没有这个病" */
    private static final List<Character> ACTION_SUFFIXES = List.of(
            '吃', '做', '过', '查', '测', '服', '用', '打', '停');
    /** 不确定表述：出现时不把否定词当作"已否认"（患者只是拿不准） */
    private static final List<String> UNCERTAIN = List.of(
            "可能", "也许", "大概", "好像", "似乎", "不确定", "不知道");
    /** 外部语境：否定落在家族史/并发症上时，不代表患者本人没有这个情况 */
    private static final List<String> EXCLUDED_CONTEXT = List.of(
            "家族", "父母", "家人", "并发症");
    /** 公共子串必须含汉字或字母，纯数字/标点的重叠不算"提到" */
    private static final Pattern MEANINGFUL = Pattern.compile("[\\p{IsHan}A-Za-z]");

    /**
     * 否定词只在这个距离内算数：中文的否定紧贴结论（「我没有糖尿病」「不是糖尿病」）。
     * 窗口放大到 12 会把上一分句的否定带到下一个病名上——
     * 「我没有糖尿病，但是我有高血压」不该把高血压也判成否定。
     */
    private static final int NEGATION_WINDOW = 6;
    private static final int MIN_OVERLAP_LONG = 3;
    private static final int MIN_OVERLAP_SHORT = 2;

    private MemoryNegation() {
    }

    /** 当前这句话是否明确否定了这条背景（「我没有糖尿病」→ true） */
    public static boolean negated(String text, String value) {
        String t = normalize(text);
        String v = normalize(value);
        if (t.isEmpty() || v.isEmpty()) {
            return false;
        }
        for (int[] span : mentionSpans(t, v)) {
            int from = Math.max(0, span[0] - NEGATION_WINDOW);
            int to = Math.min(t.length(), span[0] + span[1] + 4);
            String window = t.substring(from, to);
            if (containsAny(window, UNCERTAIN) || containsAny(window, EXCLUDED_CONTEXT)) {
                continue;
            }
            if (containsAny(window, NEGATIONS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 患者是否在**收回**这条已记住的背景（「说错了我没有2型糖尿病」）。
     *
     * <p>比 {@link #negated} 更严：召回侧"这句否定它，本轮别注入"损失很小，
     * 收回则是**改状态**，误判会把一条正确的记忆永久停掉。因此只认"直接否认这个情况"
     * 的强否定（没有 / 不是 / 并非 …），并且：
     * <ul>
     *   <li>否定接的是动作而非情况（「我没吃糖尿病药」「我没做过糖尿病检查」）⇒ 不收回；</li>
     *   <li>「我说错了，其实我有糖尿病」是更正为"有" ⇒ 不收回；</li>
     *   <li>不确定、家族史、并发症同样不算。</li>
     * </ul>
     */
    public static boolean retracted(String message, String value) {
        String t = normalize(message);
        String v = normalize(value);
        if (t.isEmpty() || v.isEmpty()) {
            return false;
        }
        for (int[] span : mentionSpans(t, v)) {
            int from = Math.max(0, span[0] - NEGATION_WINDOW);
            String before = t.substring(from, span[0]);
            String window = t.substring(from, Math.min(t.length(), span[0] + span[1] + 4));
            if (containsAny(window, UNCERTAIN) || containsAny(window, EXCLUDED_CONTEXT)) {
                continue;
            }
            if (!before.isEmpty() && ACTION_SUFFIXES.contains(before.charAt(before.length() - 1))) {
                continue;
            }
            if (containsAny(window, RETRACTION_NEGATIONS)) {
                return true;
            }
        }
        return false;
    }

    /** 这句话是否提到了这条值（完整子串，或足够长的公共子串） */
    public static boolean mentions(String text, String value) {
        String t = normalize(text);
        String v = normalize(value);
        return !t.isEmpty() && !v.isEmpty() && !mentionSpans(t, v).isEmpty();
    }

    // ---------------- 内部 ----------------

    private static List<int[]> mentionSpans(String text, String value) {
        List<int[]> spans = new ArrayList<>();
        int index = text.indexOf(value);
        while (index >= 0) {
            spans.add(new int[]{index, value.length()});
            index = text.indexOf(value, index + 1);
        }
        if (!spans.isEmpty()) {
            return spans;
        }
        int min = value.length() <= 4 ? MIN_OVERLAP_SHORT : MIN_OVERLAP_LONG;
        String overlap = longestCommonSubstring(text, value);
        if (overlap.length() < min || !MEANINGFUL.matcher(overlap).find()) {
            return List.of();
        }
        index = text.indexOf(overlap);
        while (index >= 0) {
            spans.add(new int[]{index, overlap.length()});
            index = text.indexOf(overlap, index + 1);
        }
        return spans;
    }

    /** 最长公共连续子串（两句都很短：query ≤ 数百字、value ≤ 60 字，O(n·m) 足够） */
    static String longestCommonSubstring(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        int best = 0;
        int end = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > best) {
                        best = dp[i][j];
                        end = i;
                    }
                }
            }
        }
        return a.substring(end - best, end);
    }

    /** 抹掉疑问词但保留长度，这样窗口下标仍对得上原文 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String word : INTERROGATIVES) {
            t = t.replace(word, " ".repeat(word.length()));
        }
        return t;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
