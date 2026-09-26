package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * txt / md 的解析：**原生读**，不经 Tika。
 *
 * <p>为什么不让 Tika 一起接了：Tika 没有 Markdown 解析器（`.md` 会落到纯文本分支），
 * txt 同理——那两条等于直接读文件，多一层依赖什么也没换来。Tika 因此只剩 html 一处用处。
 *
 * <p>Markdown 顺带吃它自带的层级：`#` 开头的行是标题块，其余按空行分段成正文块。
 * 纯文本没有结构可言，整篇按空行分段——切分层再按长度递归切。
 */
@Slf4j
@Component
public class PlainTextLayoutParser implements LayoutParser {

    /** Markdown 标题行：最多三级缩进的 # */
    private static final java.util.regex.Pattern MD_HEADING =
            java.util.regex.Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*)$");

    @Override
    public List<LayoutBlock> parse(InputStream in, String fileName) {
        String text = TextDecoder.readAll(in, fileName);
        boolean markdown = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
        return markdown ? parseMarkdown(text) : parsePlain(text);
    }

    /** Markdown：标题行 = 边界，其余按空行分段 */
    private List<LayoutBlock> parseMarkdown(String text) {
        List<LayoutBlock> blocks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            var matcher = MD_HEADING.matcher(line);
            if (matcher.matches()) {
                flush(buffer, blocks);
                blocks.add(LayoutBlock.title(matcher.group(1).trim(), null));
            } else {
                buffer.append(line).append('\n');
            }
        }
        flush(buffer, blocks);
        return blocks;
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
