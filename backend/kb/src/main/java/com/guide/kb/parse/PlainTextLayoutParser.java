package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * txt / md 的解析：**原生读**，不经 Tika。
 *
 * <p>为什么不让 Tika 一起接了：Tika 没有 Markdown 解析器（`.md` 会落到纯文本分支），
 * txt 同理——那两条等于直接读文件，多一层依赖什么也没换来。Tika 因此只剩 html 一处用处。
 *
 * <p><b>Markdown 的标题有三档信号</b>（2026-10-01 补齐后两档 + 表格）：
 * <ol>
 *   <li>{@code #} 行——markdown 自带层级</li>
 *   <li>**整行粗体**（{@code **xxx**} 独占一行）——公众号/Clippings 导出的小标题就是这个形态。
 *       它是**版面信号不是猜**：行内粗体（{@code **强调**后面还有正文}）不算，那是强调；
 *       以句号/冒号结尾的整行粗体也不算（那多半是引导句或强调句）</li>
 *   <li>章节编号（与 DocumentMind 的文本级兜底同一套判据）：中文序号 {@code 一、} / {@code 第一章}、
 *       阿拉伯两级 {@code 1.1}（编号后必须还有中文）；md 另收 {@code 1、}——顿号版本不是
 *       markdown 列表标记，而 {@code 1.} 是，所以单级点号一律不收</li>
 * </ol>
 * 纯文本没有结构可言，整篇按空行分段——切分层再按长度递归切。
 *
 * <p><b>Markdown 表格切成 TABLE 块</b>：表头行 + 分隔行（{@code |---|}）起，连续含 {@code |} 的行
 * 整块取出；块内文本**去掉分隔行、单元格用全角 `｜` 连接**（{@code 骨科 ｜ 门诊楼 3F}）——
 * 它是完整语义单元（一行"症状｜科室"本身就是一条知识），交给切分层整块入 chunk、不参与正文切分。
 *
 * <p><b>Markdown 开头的 YAML front matter 要剥掉</b>（2026-10-01 修）：那是元数据
 * （`title` / `source` / `author` / `tags`…），不是正文。不剥有两个后果——它会作为正文被向量化
 * 污染检索；以及**模型切分时会被模型自己剥掉**（模型认得出那是元数据），于是逐字校验判"丢字"、
 * 整份文档入库失败（真实案例：Clippings 导出的一篇 md，421 字 front matter 让全文校验差 398 字）。
 */
@Slf4j
@Component
public class PlainTextLayoutParser implements LayoutParser {

    /** Markdown 标题行：最多三级缩进的 # */
    private static final java.util.regex.Pattern MD_HEADING =
            java.util.regex.Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*)$");

    /** **整行粗体**：`**xxx**` 或 `__xxx__` 独占一行（行内粗体不匹配——中间有 `**` 会被下面的内层检查挡掉） */
    private static final Pattern MD_BOLD_LINE = Pattern.compile("^(?:\\*\\*|__)(.+?)(?:\\*\\*|__)$");

    /** 无歧义的章节编号前缀：`第一章` / `第 2 节` / `一、`（与 DocumentMind 同一套） */
    private static final Pattern CN_SECTION = Pattern.compile(
            "第\\s*[一二三四五六七八九十百零0-9]+\\s*[章节条篇]"
                    + "|[一二三四五六七八九十]+、");

    /** 阿拉伯**多级**编号（`1.1` / `4.2.1.1`）；单级 `1.` 是 markdown 列表标记，不收 */
    private static final Pattern ARABIC_SECTION = Pattern.compile("[0-9]+(?:\\.[0-9]+)+");

    /** md 独有：`1、`（顿号）——公众号小标题常见，且顿号不是 markdown 列表标记 */
    private static final Pattern ARABIC_DUNHAO = Pattern.compile("[0-9]+、");

    /** 中文（用来判"编号之后还有没有标题文字"） */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /** 标题长度上限：再长就已经是一句话，不是标题 */
    private static final int HEADING_MAX_CHARS = 80;

    /**
     * 开头的 YAML front matter：第一行是 `---`，到下一个单独成行的 `---` 结束。
     * **只认文件开头**——正文里的 `---` 是分隔线，动了就是删内容。
     */
    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---[ \\t]*\\r?\\n.*?\\r?\\n---[ \\t]*\\r?\\n", Pattern.DOTALL);

    @Override
    public List<LayoutBlock> parse(InputStream in, String fileName) {
        String text = TextDecoder.readAll(in, fileName);
        boolean markdown = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
        return markdown ? parseMarkdown(stripFrontMatter(text, fileName)) : parsePlain(text);
    }

    /** 剥掉 YAML front matter（没有就原样返回；没闭合的畸形块也不动，宁可不剥也不要吃掉正文） */
    private String stripFrontMatter(String text, String fileName) {
        var matcher = FRONT_MATTER.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        log.debug("Markdown front matter 已剥除：{}｜前 {} 字", fileName, matcher.end());
        return text.substring(matcher.end());
    }

    /** Markdown：标题（三档信号）= 边界，表格整块取出，其余累积成正文块 */
    private List<LayoutBlock> parseMarkdown(String text) {
        List<LayoutBlock> blocks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] lines = text.split("\r?\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (isTableStart(lines, i)) {
                int end = tableEnd(lines, i);
                flush(buffer, blocks);
                // 整块入 TABLE：正文形态见 tableText（去掉分隔行、单元格用全角｜连接）
                blocks.add(LayoutBlock.table(tableText(lines, i, end), null));
                i = end;
                continue;
            }
            String heading = headingText(lines[i]);
            if (heading != null) {
                flush(buffer, blocks);
                blocks.add(LayoutBlock.title(heading, null));
            } else {
                buffer.append(lines[i]).append('\n');
            }
            i++;
        }
        flush(buffer, blocks);
        return blocks;
    }

    /**
     * 标题判定（三档，从强到弱）。都不命中返回 null——**宁可漏判也不误提升**：
     * 误提升的代价不只是多一个边界，后一个标题还会顶掉前一个，那一段的真标题在溯源里就没了。
     */
    private String headingText(String line) {
        var md = MD_HEADING.matcher(line);
        if (md.matches()) {
            return md.group(1).trim();
        }
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        String bold = boldLineText(stripped);
        if (bold != null) {
            return bold;
        }
        return looksLikeSectionHeading(stripped) ? stripped : null;
    }

    /** 整行粗体 → 去掉标记后的标题文字；行内粗体、以句号/冒号结尾的强调句不算 */
    private String boldLineText(String stripped) {
        var matcher = MD_BOLD_LINE.matcher(stripped);
        if (!matcher.matches()) {
            return null;
        }
        String inner = matcher.group(1).strip();
        if (inner.isEmpty() || inner.length() > HEADING_MAX_CHARS) {
            return null;
        }
        // 行内粗体的另一种形态：`**a** 和 **b**` 也会被上面的正则吞成一条，用"内层不含 **"挡掉
        if (inner.contains("**") || inner.contains("__")) {
            return null;
        }
        if (endsWithSentence(inner)) {
            return null;
        }
        return inner;
    }

    /** 句号/冒号结尾的整行粗体多半是强调句或引导句（`**注意：**`、`**……以免延误病情。**`） */
    private boolean endsWithSentence(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '。' || last == '：' || last == ':';
    }

    /**
     * 文本级标题兜底：短、且以章节编号开头。判据与 {@code DocMindLayoutReader.looksLikeSectionHeading}
     * 同源（那边是服务端版面块、没有 markdown 标记可用，只能靠编号；md 多了"整行粗体"这一档，按理更准）。
     */
    private boolean looksLikeSectionHeading(String text) {
        if (text.isEmpty() || text.length() > HEADING_MAX_CHARS) {
            return false;
        }
        if (CN_SECTION.matcher(text).lookingAt() || ARABIC_DUNHAO.matcher(text).lookingAt()) {
            return true;
        }
        var arabic = ARABIC_SECTION.matcher(text);
        // 编号之后必须还有中文：`10.0.1.1`、`114.114.114.114`、`1.2 kg` 这些"整行是值"的照样挡住
        return arabic.lookingAt() && CJK.matcher(text.substring(arabic.end())).find();
    }

    // ---------- Markdown 表格 ----------

    /** 表格起点：本行有 `|` 且**下一行是分隔行**（`|---|:--:|`）——只有两条同时成立才算表格 */
    private boolean isTableStart(String[] lines, int index) {
        String row = lines[index].strip();
        if (!row.startsWith("|") || row.indexOf('|', 1) < 0) {
            return false;
        }
        return index + 1 < lines.length && isDelimiterRow(lines[index + 1]);
    }

    /** 分隔行：只由 `|` `-` `:` 和空白组成，且至少 3 个连字符（`|-|` 太短，不当表格） */
    private boolean isDelimiterRow(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        int dashes = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '-') {
                dashes++;
            } else if (c != '|' && c != ':' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return dashes >= 3;
    }

    /** 表格结束：第一行不再含 `|`（空行会断表） */
    private int tableEnd(String[] lines, int start) {
        int i = start;
        while (i < lines.length && lines[i].contains("|")) {
            i++;
        }
        return i;
    }

    /**
     * 表格块的正文形态：**去掉 `|---|` 分隔行**，单元格用全角 `｜` 连接成"科室 ｜ 位置"。
     *
     * <p>为什么不留原样 markdown：这段文本会进 chunk——既被向量化，也会原样出现在推荐卡的
     * 「判断依据」里给患者看。`|---|---|` 对语义没贡献、对读者是噪声；换全角竖线是为了让它
     * 一眼就是"表格的一行"，不再像 markdown 语法。
     */
    private String tableText(String[] lines, int start, int end) {
        List<String> rows = new ArrayList<>();
        for (int i = start; i < end; i++) {
            String row = lines[i].strip();
            if (row.isEmpty() || isDelimiterRow(row)) {
                continue;
            }
            String body = row.replaceAll("^\\|+", "").replaceAll("\\|+$", "");
            List<String> cells = new ArrayList<>();
            for (String cell : body.split("\\|", -1)) {
                cells.add(cell.strip());
            }
            rows.add(String.join(" ｜ ", cells));
        }
        return String.join("\n", rows);
    }

    /** 纯文本：按空行分段，没有标题块（切分层据此判定"结构切给不出边界"） */
    private List<LayoutBlock> parsePlain(String text) {
        List<LayoutBlock> blocks = new ArrayList<>();
        for (String paragraph : text.split("\n\\s*\n")) {
            if (!paragraph.isBlank()) {
                blocks.add(LayoutBlock.text(paragraph.strip(), null));
            }
        }
        return blocks;
    }

    private void flush(StringBuilder buffer, List<LayoutBlock> blocks) {
        String paragraph = buffer.toString().strip();
        buffer.setLength(0);
        if (!paragraph.isEmpty()) {
            blocks.add(LayoutBlock.text(paragraph, null));
        }
    }

}
