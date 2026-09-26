package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;

import java.io.InputStream;
import java.util.List;

/**
 * 本地解析器：把一份**本地可解析**的文件变成版面块（txt / md / html 三条路）。
 * pdf / docx / png 不走这里——它们经 llm 适配层调 DocumentMind，是唯一路径、不降级。
 */
public interface LayoutParser {

    /**
     * @param fileName 原始文件名（含扩展名），用于日志与格式自检
     * @return 按阅读顺序排好的版面块；**空列表表示一个字都没解析出来**（调用方按产出异常处理）
     */
    List<LayoutBlock> parse(InputStream in, String fileName);
}
