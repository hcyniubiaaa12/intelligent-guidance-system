package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * html 的解析：**Tika 的唯一用处**（pdf/docx 走 DocumentMind，Tika 不接）。
 *
 * <p>Tika 直接给的是纯文本，丢掉了标题层级——而 HTML 文档里 {@code <h1>/<h2>} 正是
 * 结构切的边界。所以这里多走一步：让 Tika 输出 XHTML（保留标签），再用 JDK 自带的
 * DOM 走一遍，把 h1–h6 收成标题块、段落收成正文块、表格收成 markdown 表格块。
 * 不额外引 HTML 解析库——XHTML 是合法 XML，JDK 就够。
 *
 * <p>XHTML 解析失败（html 太脏、Tika 输出不是良构 XML）时退回纯文本块：**这是同一条
 * html 路径内部的形态回落，不是 pdf/docx 的降级路径**——后者的产出质量不可比，
 * 才会让知识库混进两种切分风格。
 */
@Slf4j
@Component
public class HtmlLayoutParser implements LayoutParser {

    /** 收成标题块的标签 */
    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** 收成正文块的块级标签（td/th 也在内：表格之外它们同样是内容） */
    private static final Set<String> TEXT_BLOCKS = Set.of("p", "li", "blockquote", "pre", "dd", "dt", "figcaption");

    /** 跳过整棵子树的标签：脚本样式不是语料 */
    private static final Set<String> SKIP = Set.of("script", "style", "noscript", "head");

    private final HtmlParser parser = new HtmlParser();

    @Override
    public List<LayoutBlock> parse(InputStream in, String fileName) {
        // 解码走本地这一套（UTF-8 严格 → GBK 回落），再把 UTF-8 字节交给 Tika 并**告知编码**：
        // 让 Tika 自己探测就得再引一个编码探测模块，而且同一份中文文件在 txt 与 html
        // 两条路径上会得到两种结果——一个正确一个乱码，排查时根本想不到是解码器的差别。
        //
        // 注意：这里必须设 **Content-Type 里的 charset**，不是 Metadata.CONTENT_ENCODING——
        // Tika 的 AutoDetectReader 依次试 SPI 探测器、再读 Content-Type 的 charset 参数，
        // 不看 CONTENT_ENCODING；探测器一个都不在 classpath 上（只引了 html 模块）时，
        // 就只剩 charset 参数这一条路，不设它会直接抛「Failed to detect the character encoding」。
        String text = TextDecoder.readAll(in, fileName);
        String xhtml;
        try {
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, "text/html; charset=" + StandardCharsets.UTF_8.name());
            ToXMLContentHandler handler = new ToXMLContentHandler();
            parser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    handler, metadata, new ParseContext());
            xhtml = handler.toString();
        } catch (Exception e) {
            throw new IllegalStateException("html 解析失败：" + fileName, e);
        }
        Document doc = parseXml(xhtml);
        if (doc == null) {
            log.warn("Tika 输出的 XHTML 不是良构 XML，退回纯文本块：{}", fileName);
            return List.of(LayoutBlock.text(plainText(xhtml), null));
        }
        List<LayoutBlock> blocks = new ArrayList<>();
        Element body = firstElement(doc, "body");
        walk(body == null ? doc.getDocumentElement() : body, blocks);
        return blocks;
    }

    /** 深度优先遍历，按块级标签收集；表格整棵子树单独处理（见 tableToMarkdown） */
    private void walk(Node node, List<LayoutBlock> blocks) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getTextContent().strip();
                if (!text.isEmpty()) {
                    blocks.add(LayoutBlock.text(text, null));
                }
                continue;
            }
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = localName(child);
            if (SKIP.contains(tag)) {
                continue;
            }
            if (HEADINGS.contains(tag)) {
                String text = child.getTextContent().strip();
                if (!text.isEmpty()) {
                    blocks.add(LayoutBlock.title(text, null));
                }
            } else if ("table".equals(tag)) {
                String markdown = tableToMarkdown((Element) child);
                if (!markdown.isBlank()) {
                    blocks.add(LayoutBlock.table(markdown, null));
                }
            } else if (TEXT_BLOCKS.contains(tag)) {
                if (!containsTable((Element) child)) {
                    String text = child.getTextContent().strip();
                    if (!text.isEmpty()) {
                        blocks.add(LayoutBlock.text(text, null));
                    }
                } else {
                    walk(child, blocks);
                }
            } else {
                // div / section / article 这类纯容器：继续往下走
                walk(child, blocks);
            }
        }
    }

    private boolean containsTable(Element element) {
        return element.getElementsByTagNameNS("*", "table").getLength() > 0
                || element.getElementsByTagName("table").getLength() > 0;
    }

    /**
     * 表格 → markdown 表格。表格是分诊知识里信息密度最高的部分（一行 = 一个
     * 「症状 → 科室 → 鉴别方向」的完整语义单元），丢掉它等于主动制造解析遗漏。
     *
     * <p>不处理 colspan/rowspan：跨行跨列的合并单元格无法用 markdown 表格如实表达，
     * 硬拼只会得到一张错位的表——宁可让它以单元格文本原样出现。
     */
    private String tableToMarkdown(Element table) {
        List<Element> rows = elementsByTagName(table, "tr");
        if (rows.isEmpty()) {
            return "";
        }
        List<List<String>> grid = new ArrayList<>();
        int columns = 0;
        for (Element row : rows) {
            List<String> cells = new ArrayList<>();
            for (Element cell : elementsByTagName(row, "td", "th")) {
                cells.add(cell.getTextContent().replaceAll("\\s+", " ").strip());
            }
            if (!cells.isEmpty()) {
                grid.add(cells);
                columns = Math.max(columns, cells.size());
            }
        }
        if (grid.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < grid.size(); i++) {
            List<String> cells = grid.get(i);
            md.append('|');
            for (int c = 0; c < columns; c++) {
                md.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
            }
            md.append('\n');
            if (i == 0) {
                md.append('|').append(" --- |".repeat(columns)).append('\n');
            }
        }
        return md.toString().strip();
    }

    private List<Element> elementsByTagName(Element root, String... names) {
        List<Element> found = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = localName(node);
            for (String name : names) {
                if (name.equals(tag)) {
                    found.add((Element) node);
                    break;
                }
            }
        }
        return found;
    }

    /** 只取直接子节点里的同名元素（找 body 用，避免命中嵌套的 body） */
    private Element firstElement(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    /** 带命名空间时 getLocalName 才有值（Tika 的 XHTML 带 xmlns） */
    private String localName(Node node) {
        String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    private String plainText(String xhtml) {
        return xhtml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }

    /** 关掉 DTD 加载（XXE），解析失败返回 null 由调用方回落 */
    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("XHTML 解析失败：{}", e.getMessage());
            return null;
        }
    }
}
