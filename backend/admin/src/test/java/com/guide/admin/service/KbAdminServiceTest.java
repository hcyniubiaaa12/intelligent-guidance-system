package com.guide.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.admin.dto.KbAdminDTO;
import com.guide.async.service.IngestPipeline;
import com.guide.async.service.IngestTaskService;
import com.guide.common.model.LayoutBlock;
import com.guide.kb.entity.Dept;
import com.guide.kb.entity.DeptMapping;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.entity.MedicalTerm;
import com.guide.kb.enums.MappingSource;
import com.guide.kb.enums.TermSource;
import com.guide.kb.enums.TermType;
import com.guide.kb.service.DeptMappingService;
import com.guide.kb.service.DeptService;
import com.guide.kb.service.KbDocService;
import com.guide.kb.service.MedicalTermService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理端知识库的**编排**：把 kb 的实体翻成页面要看的形状。
 *
 * <p>这里的规则只有两类，但都属于"错了页面会撒谎"的那种：① 科室 id 必须换成名字
 * （前端不查表，换不出来就没人换）；② **台账里的坏数据不能挡住整页**——台账是只读留痕，
 * 一条 cross_dept_ids 坏 JSON 不该让管理员看不到其余所有行。
 */
class KbAdminServiceTest {

    private final KbDocService kbDocService = mock(KbDocService.class);
    private final DeptService deptService = mock(DeptService.class);
    private final DeptMappingService deptMappingService = mock(DeptMappingService.class);
    private final MedicalTermService medicalTermService = mock(MedicalTermService.class);
    private final IngestPipeline ingestPipeline = mock(IngestPipeline.class);
    private final IngestTaskService ingestTaskService = mock(IngestTaskService.class);

    private final KbAdminService service = new KbAdminService(kbDocService, deptService, deptMappingService,
            medicalTermService, ingestPipeline, ingestTaskService, new ObjectMapper());

    @Test
    @DisplayName("科室蓝本：文档数与切片数分别来自两处统计，缺的补 0（不是 null）")
    void deptsCarriesDocAndChunkCounts() {
        when(deptService.listAll()).thenReturn(List.of(dept("d1", "骨科", 1), dept("d2", "皮肤科", 0)));
        when(kbDocService.countByDept()).thenReturn(java.util.Map.of("d1", 3L));
        when(kbDocService.countChunksByDept()).thenReturn(java.util.Map.of("d1", 42L));

        List<KbAdminDTO.DeptVO> depts = service.depts();

        assertThat(depts).hasSize(2);
        assertThat(depts.get(0).getDocCount()).isEqualTo(3L);
        assertThat(depts.get(0).getChunkCount()).isEqualTo(42L);
        assertThat(depts.get(1).getDocCount()).isZero();
        assertThat(depts.get(1).getChunkCount()).isZero();
        assertThat(depts.get(1).getEnabled()).isZero();
    }

    @Test
    @DisplayName("映射台账：主科室与交叉科室都换成名字，交叉科室用顿号连接")
    void mappingsResolveDeptNames() {
        Page<DeptMapping> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(mapping("m1", "颈肩僵硬伴手指麻木", "d1", "[\"d2\",\"d3\"]")));
        when(deptMappingService.page(any(), anyLong(), anyLong())).thenReturn(page);
        when(deptService.listAll()).thenReturn(List.of(
                dept("d1", "骨科", 1), dept("d2", "神经内科", 1), dept("d3", "心血管内科", 1)));

        KbAdminDTO.PageVO<KbAdminDTO.MappingVO> vo = service.mappings(null, 1, 10);

        assertThat(vo.getTotal()).isEqualTo(1);
        assertThat(vo.getRecords().get(0).getMainDeptName()).isEqualTo("骨科");
        assertThat(vo.getRecords().get(0).getCrossDeptNames()).isEqualTo("神经内科、心血管内科");
        assertThat(vo.getRecords().get(0).getSource()).isEqualTo("feedback");
    }

    @Test
    @DisplayName("台账里的坏 JSON / 已删科室：原样展示、不抛异常（只读留痕，坏数据不该挡住整页）")
    void mappingsTolerateBadRows() {
        Page<DeptMapping> page = new Page<>(1, 10);
        page.setRecords(List.of(mapping("m1", "症状A", "gone", "不是 JSON")));
        when(deptMappingService.page(any(), anyLong(), anyLong())).thenReturn(page);
        when(deptService.listAll()).thenReturn(List.of());

        KbAdminDTO.MappingVO row = service.mappings(null, 1, 10).getRecords().get(0);

        assertThat(row.getMainDeptName()).isEqualTo("（科室已删除）");
        assertThat(row.getCrossDeptNames()).isEqualTo("不是 JSON");
    }

    @Test
    @DisplayName("术语白名单：枚举按 code 出（前端认小写编码值，不认大写常量名）")
    void termsUseEnumCodes() {
        Page<MedicalTerm> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(term("t1", "反酸", TermType.SYMPTOM, TermSource.LLM_EXTRACT, 0)));
        when(medicalTermService.page(any(), any(), anyLong(), anyLong())).thenReturn(page);

        KbAdminDTO.TermVO row = service.terms(null, null, 1, 10).getRecords().get(0);

        assertThat(row.getType()).isEqualTo("symptom");
        assertThat(row.getSource()).isEqualTo("llm_extract");
        assertThat(row.getEnabled()).isZero();
    }

    @Test
    @DisplayName("启停术语：把 kb 返回的结果翻成 code 形状回给前端（页面据此翻转按钮文案）")
    void toggleTermReturnsCodeShape() {
        when(medicalTermService.toggle(eq("t1")))
                .thenReturn(term("t1", "反酸", TermType.SYMPTOM, TermSource.MANUAL, 1));

        KbAdminDTO.TermVO vo = service.toggleTerm("t1");

        assertThat(vo.getEnabled()).isEqualTo(1);
        assertThat(vo.getType()).isEqualTo("symptom");
        assertThat(vo.getSource()).isEqualTo("manual");
    }

    @Test
    @DisplayName("查看切片：标「本标题下第几块」——遇新标题重置、只数 text 片、表格与标题不占号")
    void chunksNumberWithinSection() {
        when(kbDocService.listChunks("doc1")).thenReturn(List.of(
                chunk("c1", 1, LayoutBlock.BlockType.TEXT, "腰痛挂什么科"),     // 开头无标题片，自成一段
                chunk("c2", 2, LayoutBlock.BlockType.TEXT, "腰痛挂什么科"),
                chunk("c3", 3, LayoutBlock.BlockType.TITLE, "注意事项"),        // 标题一
                chunk("c4", 4, LayoutBlock.BlockType.TEXT, "注意事项"),
                chunk("c5", 5, LayoutBlock.BlockType.TABLE, "注意事项"),        // 表格不占号，也不打断计数
                chunk("c6", 6, LayoutBlock.BlockType.TEXT, "注意事项"),
                chunk("c7", 7, LayoutBlock.BlockType.TITLE, "注意事项"),        // 同名标题——仍是新的一节
                chunk("c8", 8, LayoutBlock.BlockType.TEXT, "注意事项")));

        List<KbAdminDTO.ChunkVO> vos = service.chunks("doc1");

        assertThat(vos).hasSize(8);
        assertThat(vos.get(0).getNoInSection()).isEqualTo(1);
        assertThat(vos.get(0).getSectionTotal()).isEqualTo(2);
        // 标题片是节名本身、表格片是整块语义单元——都不占号，前端见 null 就不显示
        assertThat(vos.get(2).getNoInSection()).isNull();
        assertThat(vos.get(4).getNoInSection()).isNull();
        assertThat(vos.get(4).getSectionTotal()).isNull();
        // 表格被跳过，正文接上第 2 块；这一节共 2 块 text
        assertThat(vos.get(5).getNoInSection()).isEqualTo(2);
        assertThat(vos.get(5).getSectionTotal()).isEqualTo(2);
        // 重置点：c7 与 c3 的标题**文字相同**，但它们是两个结构上独立的节。
        // 若有人改成"按 title 文字分组"，这里会算成 3 —— 这正是本用例要钉住的口径
        assertThat(vos.get(7).getNoInSection()).isEqualTo(1);
        assertThat(vos.get(7).getSectionTotal()).isEqualTo(1);
        // 类型原样透出（枚举编码值）：标题片的 content 与 title 是同一个字符串，没有这个字段
        // 管理端只能靠"两行长得一样"去猜，看起来像重复入库
        assertThat(vos.get(0).getChunkType()).isEqualTo("text");
        assertThat(vos.get(2).getChunkType()).isEqualTo("title");
        assertThat(vos.get(4).getChunkType()).isEqualTo("table");
    }

    private KbChunk chunk(String id, int seq, LayoutBlock.BlockType type, String title) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setSeq(seq);
        chunk.setChunkType(type);
        chunk.setTitle(title);
        return chunk;
    }

    private Dept dept(String id, String name, int enabled) {
        Dept dept = new Dept();
        dept.setId(id);
        dept.setName(name);
        dept.setEnabled(enabled);
        return dept;
    }

    private DeptMapping mapping(String id, String symptom, String mainDeptId, String crossIds) {
        DeptMapping mapping = new DeptMapping();
        mapping.setId(id);
        mapping.setSymptom(symptom);
        mapping.setMainDeptId(mainDeptId);
        mapping.setCrossDeptIds(crossIds);
        mapping.setSource(MappingSource.FEEDBACK);
        return mapping;
    }

    private MedicalTerm term(String id, String text, TermType type, TermSource source, int enabled) {
        MedicalTerm term = new MedicalTerm();
        term.setId(id);
        term.setTerm(text);
        term.setType(type);
        term.setSource(source);
        term.setEnabled(enabled);
        return term;
    }
}
