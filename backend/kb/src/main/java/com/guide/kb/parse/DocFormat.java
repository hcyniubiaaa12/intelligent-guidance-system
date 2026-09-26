package com.guide.kb.parse;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 知识文档的**格式白名单**（链路 B）：判定权在后端，前端只做即时提示。
 *
 * <p>白名单刻意很窄——**支持但没验证过的格式，是静默的垃圾语料来源**：上传成功、
 * 进度条走完、状态"已完成"，然后切出一堆乱码进向量库，没有任何人会发现；
 * 而"不支持的格式"会当场报错。两种失败的可见度差着一个数量级。
 *
 * <p>分流按**扩展名**做（名字改错就会走错解析器 → 解析失败 → 一条 failed 记录），
 * 不再叠一层魔数判定：它防的是"恶意上传"，而这里的上传者是管理员，判错格式的代价
 * 只是一条留痕、可重试的失败记录。
 *
 * <p><b>硬规则</b>：{@link Route#TIKA} 只接 html，**不得接 pdf/docx**——Tika 本身就能解析
 * 这两种格式，躺在依赖里迟早有人把解析失败的 pdf 丢给它"试试"，那条刚被否掉的降级路径
 * 会顺着依赖慢慢爬回来。
 */
@Getter
@RequiredArgsConstructor
public enum DocFormat {

    PDF("pdf", Route.DOCMIND),
    DOCX("docx", Route.DOCMIND),
    PNG("png", Route.DOCMIND),
    TXT("txt", Route.LOCAL_TEXT),
    MD("md", Route.LOCAL_TEXT),
    HTML("html", Route.TIKA);

    /** 解析路径 */
    public enum Route {
        /** 走 llm 适配层调 DocumentMind（pdf/docx/png 的**唯一**路径，不降级） */
        DOCMIND,
        /** 原生读文本 */
        LOCAL_TEXT,
        /** Tika（只接 html） */
        TIKA
    }

    /** 扩展名（小写，不含点） */
    private final String extension;

    private final Route route;

    /** 是外部解析（需要提交任务号、走轮询）还是本地即时解析 */
    public boolean external() {
        return route == Route.DOCMIND;
    }

    /** 白名单的展示形态，用于拒绝文案与上传界面提示 */
    public static String supported() {
        return Arrays.stream(values()).map(DocFormat::getExtension).collect(Collectors.joining(" / "));
    }

    /** 拒绝上传时的统一文案：**判定权在后端**，这句话就是给管理员看的「该传什么」 */
    public static String unsupportedMessage() {
        return "不支持的文件格式：仅支持 " + supported();
    }

    /**
     * 按文件名判定格式。
     *
     * @return 命中的格式；**不在白名单则返回 null**（调用方负责拒绝，不要在这里兜底成 txt）
     */
    public static DocFormat fromFileName(String fileName) {
        String name = stripQueryAndPath(fileName);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (DocFormat format : values()) {
            if (format.extension.equals(ext)) {
                return format;
            }
        }
        return null;
    }

    /** 原始文件名（含扩展名），传给外部解析服务判格式用 */
    public static String baseName(String path) {
        return stripQueryAndPath(path);
    }

    /**
     * 去掉查询串与路径，只留文件名。
     * 查询串要去的理由：`file_url` 可能是带签名的链接（`.../a.pdf?X-Amz-Signature=...`），
     * 不去掉的话扩展名会变成 `pdf?X-Amz-Signature=abc`。
     */
    private static String stripQueryAndPath(String path) {
        if (path == null) {
            return "";
        }
        String name = path;
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
