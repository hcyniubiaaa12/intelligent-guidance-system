package com.guide.kb.service;

import com.guide.auth.service.SysConfigService;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.model.LayoutBlock;
import com.guide.common.util.MinioUtil;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.parse.HtmlLayoutParser;
import com.guide.kb.parse.PlainTextLayoutParser;
import com.guide.kb.split.DocSplitter;
import com.guide.llm.client.DocParseModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切分入库这一段的边界：**写入是分批的，所以"文档还在不在"必须每批复查**。
 * 只在起手查一次的话，删掉文档之后剩下的批照样写三处——那些切片在 MySQL 里活着、检索也命中，
 * 而文档行已经没了，页面上再也找不到它们，也没地方清理。
 */
class DocIngestServiceTest {

    private static final String DOC_ID = "1001";

    private final KbDocService kbDocService = mock(KbDocService.class);
    private final DocSplitter docSplitter = mock(DocSplitter.class);
    private final ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
    private final PlainTextLayoutParser plainTextLayoutParser = mock(PlainTextLayoutParser.class);
    private final HtmlLayoutParser htmlLayoutParser = mock(HtmlLayoutParser.class);
    private final DocParseModel docParseModel = mock(DocParseModel.class);
    private final MinioUtil minioUtil = mock(MinioUtil.class);
    private final SysConfigService sysConfigService = mock(SysConfigService.class);

    private final DocIngestService service = new DocIngestService(kbDocService, docSplitter, chunkIndexService,
            plainTextLayoutParser, htmlLayoutParser, docParseModel, minioUtil, sysConfigService);

    @BeforeEach
    void setUp() {
        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(400);
        when(kbDocService.require(DOC_ID)).thenReturn(doc());
    }

    @Test
    @DisplayName("25 片按 20 一片分两批写；中途文档被删 → 第二批不写，抛出说清原因的异常")
    void stopsWritingWhenDocumentDisappearsMidway() {
        when(docSplitter.split(any(), any(), anyString())).thenReturn(chunks(25));
        // 第一批之前还在，第二批之前已经被删
        when(kbDocService.exists(DOC_ID)).thenReturn(true, false);

        assertThatThrownBy(() -> service.splitAndIndex(DOC_ID, blocks(), total -> {
        }, done -> {
        }))
                .isInstanceOf(BizException.class)
                .hasMessage("文档已删除")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DOC_NOT_FOUND.getCode());

        ArgumentCaptor<List<ChunkInput>> written = ArgumentCaptor.forClass(List.class);
        verify(chunkIndexService, times(1)).indexChunks(eq(DOC_ID), anyString(), written.capture());
        assertThat(written.getValue()).hasSize(20);
    }

    @Test
    @DisplayName("文档一直在：分批写完，回调按真实量推进（先给总数、再给已写入数）")
    void writesEveryBatchWhenDocumentStays() {
        when(docSplitter.split(any(), any(), anyString())).thenReturn(chunks(25));
        when(kbDocService.exists(DOC_ID)).thenReturn(true);
        List<Integer> totals = new ArrayList<>();
        List<Integer> progress = new ArrayList<>();

        int total = service.splitAndIndex(DOC_ID, blocks(), totals::add, progress::add);

        assertThat(total).isEqualTo(25);
        assertThat(totals).containsExactly(25);
        assertThat(progress).containsExactly(20, 25);
        verify(chunkIndexService, times(2)).indexChunks(eq(DOC_ID), anyString(), any());
    }

    private KbDoc doc() {
        KbDoc doc = new KbDoc();
        doc.setId(DOC_ID);
        doc.setDeptId("dept-1");
        doc.setTitle("心血管内科分诊知识");
        doc.setFileUrl("kb/" + DOC_ID + "/心内科.txt");
        return doc;
    }

    private List<LayoutBlock> blocks() {
        return List.of(LayoutBlock.title("标题", null), LayoutBlock.text("正文", null));
    }

    private List<ChunkInput> chunks(int count) {
        List<ChunkInput> inputs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            inputs.add(new ChunkInput("标题", "第 " + i + " 片正文", i + 1, List.of()));
        }
        return inputs;
    }
}
