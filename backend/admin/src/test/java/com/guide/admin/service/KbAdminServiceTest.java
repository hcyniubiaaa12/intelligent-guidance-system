package com.guide.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.admin.dto.KbAdminDTO;
import com.guide.async.service.IngestPipeline;
import com.guide.async.service.IngestTaskService;
import com.guide.kb.entity.Dept;
import com.guide.kb.entity.DeptMapping;
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
