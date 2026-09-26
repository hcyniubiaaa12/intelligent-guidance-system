package com.guide.kb.split;

import com.guide.auth.service.SysConfigService;

/**
 * 切分参数（走 `sys_config`，管理端可调）。
 *
 * <p>参数化不是可选项：链路 C 根因表里 `chunk_broken`（切分破碎）的修复动作原文是
 * 「对该文档单独重新入库（**调整切分参数**）」——参数写死在代码里，这个修复动作就是**空的**：
 * 你能看出切得不好，但改不了、也重跑不了。
 *
 * @param targetLength   目标切片长度（字符）：递归切尽量往它靠，是"理想块长"
 * @param maxLength      单切片上限（字符）：超过它**必走**递归切，是硬约束
 * @param modelMinLength 模型切的触发长度：**无标题**的连续文本达到它才值得花一次模型调用
 */
public record SplitParams(int targetLength, int maxLength, int modelMinLength) {

    public static final int DEFAULT_TARGET_LENGTH = 400;
    public static final int DEFAULT_MAX_LENGTH = 800;
    public static final int DEFAULT_MODEL_MIN_LENGTH = 1200;

    public SplitParams {
        targetLength = Math.max(1, targetLength);
        // 上限不得小于目标：否则"尽量靠近 target"与"不得超过 max"直接打架
        maxLength = Math.max(targetLength, maxLength);
        // 模型切的门槛不得低于递归切的上限：低于它的话，模型切会给"本来就该被机械切掉"的文本花冤枉钱
        modelMinLength = Math.max(maxLength, modelMinLength);
    }

    public static SplitParams defaults() {
        return new SplitParams(DEFAULT_TARGET_LENGTH, DEFAULT_MAX_LENGTH, DEFAULT_MODEL_MIN_LENGTH);
    }

    /**
     * 从 `sys_config` 读（管理端可调，改完最长一分钟生效）。
     * 键缺失时回落代码侧默认值——首次部署不灌 sys_config 也能跑通流水线。
     */
    public static SplitParams from(SysConfigService config) {
        return new SplitParams(
                config.getInt(SysConfigService.KEY_CHUNK_TARGET_LENGTH, DEFAULT_TARGET_LENGTH),
                config.getInt(SysConfigService.KEY_CHUNK_MAX_LENGTH, DEFAULT_MAX_LENGTH),
                config.getInt(SysConfigService.KEY_CHUNK_MODEL_MIN_LENGTH, DEFAULT_MODEL_MIN_LENGTH));
    }

    /** 产出校验的下限：目标长度的四分之一。低于它说明这段被切碎了，语义已经断了 */
    public int minChunkLength() {
        return Math.max(1, targetLength / 4);
    }
}
