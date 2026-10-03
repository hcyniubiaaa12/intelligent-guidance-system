package com.guide.kb.seed;

import com.guide.common.util.EsChunkUtil;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.entity.Dept;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.mapper.DeptMapper;
import com.guide.kb.mapper.KbDocMapper;
import com.guide.kb.mapper.MedicalTermMapper;
import com.guide.kb.service.ChunkIndexService;
import com.guide.kb.service.DeptService;
import com.guide.kb.service.MedicalTermService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 种子装载的幂等判据：**逐科室按名判存在**，不是"科室表非空即跳过"。
 *
 * <p>后者的致命之处在已有数据的库上：只要 `dept` 表有任意一行就整体跳过，
 * 于是 {@code SeedCorpus.DEPTS} 里新增的科室**永远装不进去**（管理端「科室蓝本」tab
 * 也没有新增入口）。改判据后，无论库处在什么状态，重启都能收敛到目标科室集合；
 * 已存在的科室是运营资产，种子**不碰**它的位置/简介/启停。
 */
class SeedDataRunnerTest {

    private final DeptMapper deptMapper = mock(DeptMapper.class);
    private final KbDocMapper kbDocMapper = mock(KbDocMapper.class);
    private final MedicalTermMapper medicalTermMapper = mock(MedicalTermMapper.class);
    private final ChunkIndexService chunkIndexService = mock(ChunkIndexService.class);
    private final MedicalTermService medicalTermService = mock(MedicalTermService.class);
    private final EsChunkUtil esChunkUtil = mock(EsChunkUtil.class);
    private final DeptService deptService = mock(DeptService.class);

    private final SeedDataRunner runner = new SeedDataRunner(deptMapper, kbDocMapper,
            medicalTermMapper, chunkIndexService, medicalTermService, esChunkUtil, deptService);

    @Test
    @DisplayName("科室表非空但缺新科室：只有缺失的那个被补建并建文档（整表判存在会把它漏掉）")
    void insertsMissingDeptEvenWhenTableNotEmpty() {
        String missing = SeedCorpus.DEPTS.get(SeedCorpus.DEPTS.size() - 1).name();
        // 除 missing 外全部已存在（模拟"库非空、但新增了科室"的真实场景）
        when(deptService.findByName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return name.equals(missing) ? null : dept(name);
        });
        when(medicalTermMapper.selectCount(null)).thenReturn(72L);

        runner.run(null);

        ArgumentCaptor<Dept> deptCaptor = ArgumentCaptor.forClass(Dept.class);
        verify(deptMapper, times(1)).insert(deptCaptor.capture());
        assertThat(deptCaptor.getValue().getName()).isEqualTo(missing);

        ArgumentCaptor<KbDoc> docCaptor = ArgumentCaptor.forClass(KbDoc.class);
        verify(kbDocMapper, times(1)).insert(docCaptor.capture());
        assertThat(docCaptor.getValue().getTitle()).contains(missing);
        // 新科室切片留空：文档仍建，chunk_total=0 是「科室已建、语料待上传」的合法中间态
        assertThat(docCaptor.getValue().getChunkTotal()).isZero();
        verify(esChunkUtil).ensureIndex();
    }

    @Test
    @DisplayName("已存在的科室：不重复插入、不改位置/简介/启停，也不重建文档")
    void skipsExistingDeptsWithoutTouchingThem() {
        when(deptService.findByName(anyString())).thenAnswer(inv -> dept(inv.getArgument(0)));
        when(medicalTermMapper.selectCount(null)).thenReturn(72L);

        runner.run(null);

        verify(deptService, times(SeedCorpus.DEPTS.size())).findByName(anyString());
        verify(deptMapper, never()).insert(any(Dept.class));
        verify(deptMapper, never()).updateById(any());
        verify(kbDocMapper, never()).insert(any(KbDoc.class));
        verify(chunkIndexService, never()).indexChunks(anyString(), anyString(), anyList());
        verify(esChunkUtil, never()).ensureIndex();
    }

    @Test
    @DisplayName("带语料的既有科室形态（空库首装）：切片照常入库")
    void seedsDeptWithChunksOnEmptyDatabase() {
        when(deptService.findByName(anyString())).thenReturn(null);
        when(medicalTermMapper.selectCount(null)).thenReturn(0L);

        runner.run(null);

        // 12 个种子科室全部补建
        verify(deptMapper, times(SeedCorpus.DEPTS.size())).insert(any(Dept.class));
        // 前 7 个科室各 2 条切片 ⇒ 有 7 次非空切片入库
        ArgumentCaptor<List<ChunkInput>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkIndexService, times(SeedCorpus.DEPTS.size())).indexChunks(any(), any(), captor.capture());
        long nonEmpty = captor.getAllValues().stream().filter(l -> !l.isEmpty()).count();
        assertThat(nonEmpty).isEqualTo(7);
    }

    private Dept dept(String name) {
        Dept dept = new Dept();
        dept.setId(name + "-id");
        dept.setName(name);
        dept.setEnabled(1);
        dept.setLocation("由种子/管理端维护的位置");
        dept.setIntro("由种子/管理端维护的简介");
        return dept;
    }
}
