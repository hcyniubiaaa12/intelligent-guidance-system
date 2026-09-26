package com.guide.kb.split;

/**
 * 切分产出校验未通过。
 *
 * <p>消息本身就是写给**要动手的人**看的 {@code fail_reason}（"这份文档为什么没进来、下一步做什么"），
 * 所以带上数字判据——它们是可行动的。这与链路 A「异常原文白名单式映射」是同一条规矩：
 * 上游 SDK 的异常原文只进日志，进不了这里。
 */
public class SplitValidationException extends RuntimeException {

    public SplitValidationException(String message) {
        super(message);
    }
}
