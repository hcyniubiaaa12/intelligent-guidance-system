package com.guide.memory.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 记忆类提示词的 JSON 输出解析（抽取与扩写共用一份口径）。
 *
 * <p>模型习惯把 JSON 包进 ``` 围栏或前后加解释：截取第一个 {@code {} 到最后一个 {@code }}。
 * 两处各自实现的话，迟早一边能容忍围栏、另一边不能，症状是「同一条提示词换个调用方就解析失败」。
 */
public final class MemoryJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemoryJsonSupport() {
    }

    /**
     * 把模型输出解析成 JSON 对象。
     *
     * @throws IllegalArgumentException 输出为空 / 不是 JSON 对象 / JSON 无法解析
     */
    public static JsonNode parseObject(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String cleaned = stripFence(output.strip());
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出不是 JSON 对象");
        }
        try {
            return MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("模型输出的 JSON 无法解析：" + e.getMessage(), e);
        }
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, closing).strip();
    }
}
