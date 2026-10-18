package com.guide.kb.service;

import com.guide.auth.service.SysConfigService;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.model.LayoutBlock;
import com.guide.common.util.MinioUtil;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.dto.ExtractedTerm;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.enums.TermType;
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
import static org.mockito.ArgumentMatchers.anyList;
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
    private final TermExtractor termExtractor = mock(TermExtractor.class);
    private final MedicalTermService medicalTermService = mock(MedicalTermService.class);

    private final DocIngestService service = new DocIngestService(kbDocService, docSplitter, chunkIndexService,
            plainTextLayoutParser, htmlLayoutParser, docParseModel, minioUtil, sysConfigService,
            termExtractor, medicalTermService);

    @BeforeEach
    void setUp() {
        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(400);
        when(kbDocService.require(DOC_ID)).thenReturn(doc());
        // 默认：抽取不出词，原样返回切片（多数测试不关心抽取）
        when(termExtractor.extract(eq(DOC_ID), anyList()))
                .thenAnswer(inv -> TermExtractor.ExtractionResult.none(inv.getArgument(1)));
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

    @Test
    @DisplayName("抽取在切分之后、第一批写入之前执行，且写进去的切片带着抽出的 terms")
    void extractsTermAfterSplitAndIndexesChunksWithThem() {
        List<ChunkInput> split = chunks(25);
        when(docSplitter.split(any(), any(), anyString())).thenReturn(split);
        when(kbDocService.exists(DOC_ID)).thenReturn(true);
        when(termExtractor.extract(eq(DOC_ID), anyList())).thenReturn(new TermExtractor.ExtractionResult(
                List.of(new ExtractedTerm("胸闷", TermType.SYMPTOM)),
                split.stream().map(c -> new ChunkInput(c.title(), c.content(), c.seq(),
                        List.of("胸闷"), c.type())).toList()));

        service.splitAndIndex(DOC_ID, blocks(), total -> {
        }, done -> {
        });

        // 抽取拿到的正是切分结果；写入的两批都带上了 terms
        verify(termExtractor).extract(DOC_ID, split);
        // 抽到的词整份文档只合并一次（不是每批一次）
        verify(medicalTermService, times(1)).mergeExtracted(eq(DOC_ID), eq(List.of(new ExtractedTerm("胸闷", TermType.SYMPTOM))));
        ArgumentCaptor<List<ChunkInput>> written = ArgumentCaptor.forClass(List.class);
        verify(chunkIndexService, times(2)).indexChunks(eq(DOC_ID), anyString(), written.capture());
        assertThat(written.getAllValues()).flatExtracting(l -> l)
                .allSatisfy(c -> assertThat(c.terms()).contains("胸闷"));
    }

    @Test
    @DisplayName("抽取抛异常（漏网的上抛）：入库照常完成，切片照常写入——抽取是旁路")
    void extractionFailureNeverBlocksIngest() {
        when(docSplitter.split(any(), any(), anyString())).thenReturn(chunks(3));
        when(kbDocService.exists(DOC_ID)).thenReturn(true);
        when(termExtractor.extract(eq(DOC_ID), anyList()))
                .thenThrow(new IllegalStateException("模型不可用"));

        int total = service.splitAndIndex(DOC_ID, blocks(), t -> {
        }, d -> {
        });

        assertThat(total).isEqualTo(3);
        verify(chunkIndexService).indexChunks(eq(DOC_ID), anyString(), any());
    }

    @Test
    @DisplayName("抽取成功但词库写库失败：入库照常完成，切片仍带着术语（ES 与白名单是两件事）")
    void termMergeFailureNeverBlocksIngest() {
        when(docSplitter.split(any(), any(), anyString())).thenReturn(chunks(2));
        when(kbDocService.exists(DOC_ID)).thenReturn(true);
        when(termExtractor.extract(eq(DOC_ID), anyList())).thenReturn(new TermExtractor.ExtractionResult(
                List.of(new ExtractedTerm("胸闷", TermType.SYMPTOM)),
                chunks(2)));
        when(medicalTermService.mergeExtracted(any(), any())).thenThrow(new IllegalStateException("数据库忙"));

        int total = service.splitAndIndex(DOC_ID, blocks(), t -> {
        }, d -> {
        });

        assertThat(total).isEqualTo(2);
        verify(chunkIndexService).indexChunks(eq(DOC_ID), anyString(), any());
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
