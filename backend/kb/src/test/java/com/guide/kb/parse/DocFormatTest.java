package com.guide.kb.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 格式白名单：判定权在后端，**不在白名单就是拒绝**（不兜底成 txt）。
 */
class DocFormatTest {

    @Test
    @DisplayName("白名单内的扩展名按格式分流，大小写不敏感")
    void routesWhitelistedExtensions() {
        assertThat(DocFormat.fromFileName("心内科.pdf")).isEqualTo(DocFormat.PDF);
        assertThat(DocFormat.fromFileName("a.DOCX")).isEqualTo(DocFormat.DOCX);
        assertThat(DocFormat.fromFileName("扫描件.Png")).isEqualTo(DocFormat.PNG);
        assertThat(DocFormat.fromFileName("notes.txt")).isEqualTo(DocFormat.TXT);
        assertThat(DocFormat.fromFileName("readme.md")).isEqualTo(DocFormat.MD);
        assertThat(DocFormat.fromFileName("dept.html")).isEqualTo(DocFormat.HTML);
    }

    @Test
    @DisplayName("pdf/docx/png 是外部解析，其余本地解析")
    void marksExternalRoute() {
        assertThat(DocFormat.PDF.external()).isTrue();
        assertThat(DocFormat.DOCX.external()).isTrue();
        assertThat(DocFormat.PNG.external()).isTrue();
        assertThat(DocFormat.TXT.external()).isFalse();
        assertThat(DocFormat.MD.external()).isFalse();
        assertThat(DocFormat.HTML.external()).isFalse();
    }

    @Test
    @DisplayName("不在白名单 → null，绝不回落成某个能解析的格式")
    void rejectsUnknownExtensions() {
        assertThat(DocFormat.fromFileName("病毒.exe")).isNull();
        assertThat(DocFormat.fromFileName("表.xlsx")).isNull();
        assertThat(DocFormat.fromFileName("没有扩展名")).isNull();
        assertThat(DocFormat.fromFileName("点结尾.")).isNull();
        assertThat(DocFormat.fromFileName(null)).isNull();
    }

    @Test
    @DisplayName("带路径与查询串的文件名也能取到扩展名")
    void handlesPathAndQuery() {
        assertThat(DocFormat.fromFileName("kb/2026/心内科.pdf")).isEqualTo(DocFormat.PDF);
        assertThat(DocFormat.fromFileName("kb\\心内科.docx")).isEqualTo(DocFormat.DOCX);
        assertThat(DocFormat.fromFileName("http://minio/kb/a.pdf?X-Amz-Signature=abc")).isEqualTo(DocFormat.PDF);
    }

    @Test
    @DisplayName("拒绝文案里列全白名单，管理员一眼知道该传什么")
    void listsSupportedExtensions() {
        assertThat(DocFormat.supported()).isEqualTo("pdf / docx / png / txt / md / html");
    }

    @Test
    @DisplayName("baseName 取原始文件名（含扩展名），传给外部解析服务判格式")
    void extractsBaseName() {
        assertThat(DocFormat.baseName("kb/123/心内科.docx")).isEqualTo("心内科.docx");
        assertThat(DocFormat.baseName("a.pdf?x=1")).isEqualTo("a.pdf");
        assertThat(DocFormat.baseName(null)).isEmpty();
    }
}
