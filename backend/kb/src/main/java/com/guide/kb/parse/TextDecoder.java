package com.guide.kb.parse;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 本地解析路径的**统一解码**：UTF-8 严格解 → 失败回落 GBK → 去 BOM。
 *
 * <p>为什么不让 Tika 自己探测编码：那要额外引一个编码探测模块（体积不小，而 Tika 在本项目
 * 只剩 html 一处用处）。更要紧的是**两套解码逻辑会给出两种结果**——同一份中文文件，
 * txt 走这里、html 走 Tika，一个正确一个乱码，排查时根本想不到是解码器的差别。
 *
 * <p>直接 {@code new String(bytes, UTF_8)} 会把编码错误的字节换成 U+FFFD，**静默产出乱码语料**
 * ——那正是格式白名单要防的东西，所以在解析这一步就得显式换编码或显式失败。
 */
@Slf4j
final class TextDecoder {

    /** 中文文档里 GBK 系编码仍很常见，UTF-8 解不开时回落到它 */
    private static final Charset FALLBACK_CHARSET = Charset.forName("GBK");

    private TextDecoder() {
    }

    /** 读全文并解码。非法字节在回落到 GBK 后仍无法解释时会带替换字符，由调用方按需处理 */
    static String readAll(InputStream in, String fileName) {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + fileName, e);
        }
        String text = decodeStrictly(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            log.info("文件不是合法 UTF-8，按 GBK 重新解码：{}", fileName);
            text = new String(bytes, FALLBACK_CHARSET);
        }
        // 去掉 BOM
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /** 严格解码：遇到非法字节返回 null（不产出 U+FFFD 谎报成功） */
    private static String decodeStrictly(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
