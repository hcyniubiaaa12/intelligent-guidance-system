package com.guide.common.util;

/**
 * 文本小工具：给"要给人看 / 要进快照"的地方留摘要。
 *
 * <p>存在的理由只有一个——**省略号的口径要一致**。截断散在各地各写一遍，
 * 迟早出现"有的地方加 …、有的地方加 ...、有的地方直接砍断"，而这些东西会一路进到
 * 患者看到的推荐卡和审核页的证据快照里。
 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 超长则截断并加省略号；{@code null} 当空串处理（少一层判空） */
    public static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
