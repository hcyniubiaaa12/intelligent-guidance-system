package com.guide.stats.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.util.PageUtil;
import com.guide.feedback.entity.RootCause;
import com.guide.feedback.mapper.ReviewTaskMapper;
import com.guide.feedback.mapper.RootCauseMapper;
import com.guide.kb.entity.Dept;
import com.guide.kb.mapper.DeptMapper;
import com.guide.stats.dto.DashboardDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板聚合单测：口径（分母怎么算）、环比不可比、趋势补零、根因分布与「患者挂错」精确排除。
 *
 * <p>口径是这一块的正确性核心——分母算错，页面上的百分比就是错的，所以每个排除条件都有用例。
 */
class DashboardServiceTest {

    private GuideRecordMapper guideRecordMapper;
    private ChatMessageMapper chatMessageMapper;
    private RootCauseMapper rootCauseMapper;
    private ReviewTaskMapper reviewTaskMapper;
    private DeptMapper deptMapper;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        guideRecordMapper = mock(GuideRecordMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        rootCauseMapper = mock(RootCauseMapper.class);
        reviewTaskMapper = mock(ReviewTaskMapper.class);
        deptMapper = mock(DeptMapper.class);
        service = new DashboardService(guideRecordMapper, chatMessageMapper, rootCauseMapper,
                reviewTaskMapper, deptMapper, new ObjectMapper());
    }

    /** 准确率计数行（MySQL 的 SUM 走 JDBC 回来可能是 BigDecimal，用 Number 形态覆盖） */
    private static Map<String, Object> hitsRow(long total, long top1, long top3) {
        Map<String, Object> row = new HashMap<>();
        row.put("total", total);
        row.put("top1_hit", top1);
        row.put("top3_hit", top3);
        return row;
    }

    private static RootCause rootCause(String recordId, String causesJson) {
        RootCause row = new RootCause();
        row.setRecordId(recordId);
        row.setCauses(causesJson);
        return row;
    }

    @Test
    @DisplayName("命中率 KPI：按已挂号样本算百分比，附注给环比百分点与样本量")
    void kpiRateAndComparison() {
        // 调用顺序：① 挂错 id 粗筛 → ②本期计 ③上期计 ④趋势 ⑤⑥待审核 ⑦⑧盲区 → ⑨根因分布
        when(rootCauseMapper.selectList(any())).thenReturn(List.of());
        when(guideRecordMapper.selectMaps(any()))
                .thenReturn(List.of(hitsRow(10, 7, 9)))
                .thenReturn(List.of(hitsRow(8, 5, 7)))
                .thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(0L);
        when(guideRecordMapper.selectCount(any())).thenReturn(0L);

        DashboardDTO.OverviewVO overview = service.overview(7);

        assertThat(overview.getSampleSize()).isEqualTo(10L);
        DashboardDTO.KpiVO top1 = overview.getKpis().get(0);
        assertThat(top1.getLabel()).isEqualTo("Top-1 命中率");
        assertThat(top1.getValue()).isEqualTo("70.0%");
        assertThat(top1.getRing()).isEqualTo("70%");
        assertThat(top1.getPct()).isEqualTo(0.7);
        // 上期 5/8 = 62.5% → +7.5pp
        assertThat(top1.getTone()).isEqualTo("up");
        assertThat(top1.getNote()).contains("↑ 7.5pp").contains("样本 10 条");

        DashboardDTO.KpiVO top3 = overview.getKpis().get(1);
        assertThat(top3.getValue()).isEqualTo("90.0%");
        assertThat(top3.getTone()).isEqualTo("up");
    }

    @Test
    @DisplayName("无样本时显示「—」而不是 0%，且明说上期不可比")
    void emptySampleIsNotZeroPercent() {
        when(rootCauseMapper.selectList(any())).thenReturn(List.of());
        when(guideRecordMapper.selectMaps(any()))
                .thenReturn(List.of(hitsRow(0, 0, 0)))
                .thenReturn(List.of(hitsRow(0, 0, 0)))
                .thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(0L);
        when(guideRecordMapper.selectCount(any())).thenReturn(0L);

        DashboardDTO.OverviewVO overview = service.overview(7);

        DashboardDTO.KpiVO top1 = overview.getKpis().get(0);
        assertThat(top1.getValue()).isEqualTo("—");
        assertThat(top1.getRing()).isEqualTo("—");
        assertThat(top1.getTone()).isEqualTo("flat");
        assertThat(top1.getNote()).contains("暂无已确认挂号的记录");
    }

    @Test
    @DisplayName("有本期样本但上期为空：不得伪造成环比数字")
    void previousWindowEmptyIsIncomparable() {
        when(rootCauseMapper.selectList(any())).thenReturn(List.of());
        when(guideRecordMapper.selectMaps(any()))
                .thenReturn(List.of(hitsRow(4, 4, 4)))
                .thenReturn(List.of(hitsRow(0, 0, 0)))
                .thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(0L);
        when(guideRecordMapper.selectCount(any())).thenReturn(0L);

        DashboardDTO.KpiVO top1 = service.overview(7).getKpis().get(0);

        assertThat(top1.getValue()).isEqualTo("100.0%");
        assertThat(top1.getTone()).isEqualTo("flat");
        assertThat(top1.getNote()).contains("不可比").doesNotContain("↑").doesNotContain("↓");
    }

    @Test
    @DisplayName("每日趋势：缺数据的日期补 0，不能错位")
    void trendFillsMissingDays() {
        LocalDate today = LocalDate.now();
        Map<String, Object> dayRow = new HashMap<>();
        dayRow.put("d", today.toString());
        dayRow.put("hit", 3L);
        dayRow.put("miss", 1L);

        when(rootCauseMapper.selectList(any())).thenReturn(List.of());
        when(guideRecordMapper.selectMaps(any()))
                .thenReturn(List.of(hitsRow(0, 0, 0)))
                .thenReturn(List.of(hitsRow(0, 0, 0)))
                .thenReturn(List.of(dayRow));
        when(reviewTaskMapper.selectCount(any())).thenReturn(0L);
        when(guideRecordMapper.selectCount(any())).thenReturn(0L);

        List<DashboardDTO.TrendVO> trend = service.overview(3).getTrend();

        assertThat(trend).hasSize(3);
        assertThat(trend.get(2).getDate()).isEqualTo(today.toString());
        assertThat(trend.get(2).getHit()).isEqualTo(3L);
        assertThat(trend.get(2).getMiss()).isEqualTo(1L);
        assertThat(trend.get(2).getLabel()).isEqualTo(weekLabel(today.getDayOfWeek()));
        // 前两天空数据补 0
        assertThat(trend.get(0).getHit()).isZero();
        assertThat(trend.get(0).getMiss()).isZero();
        assertThat(trend.get(0).getLabel()).isEqualTo(weekLabel(today.minusDays(2).getDayOfWeek()));
    }

    @Test
    @DisplayName("根因分布：按标注项计数、降序、坏 JSON 跳过")
    void rootCauseDistribution() {
        when(rootCauseMapper.selectList(any())).thenReturn(List.of(
                rootCause("r1", "[\"检索失败\",\"切分破碎\"]"),
                rootCause("r2", "[\"检索失败\"]"),
                rootCause("r3", "这不是 JSON")));

        List<DashboardDTO.CauseVO> causes = service.rootCauses();

        assertThat(causes).hasSize(2);
        assertThat(causes.get(0).getName()).isEqualTo("检索失败");
        assertThat(causes.get(0).getCount()).isEqualTo(2L);
        assertThat(causes.get(0).getPct()).isEqualTo(66.7);
        assertThat(causes.get(1).getName()).isEqualTo("切分破碎");
        assertThat(causes.get(1).getPct()).isEqualTo(33.3);
    }

    @Test
    @DisplayName("「患者挂错」按数组元素精确判定：LIKE 粗筛误命中的不算")
    void wrongPatientExclusionIsExact() {
        when(rootCauseMapper.selectList(any())).thenReturn(List.of(
                rootCause("r1", "[\"患者挂错\"]"),
                // LIKE「患者挂错」会命中这条，但数组元素并不等于它——不能算进去
                rootCause("r2", "[\"疑似患者挂错待确认\"]")));

        assertThat(service.wrongPatientRecordIds()).containsExactly("r1");
    }

    @Test
    @DisplayName("记录列表：结果文案与色调、置信度百分比、主诉取首条患者消息、科室名回填")
    void recordsMapping() {
        GuideRecord registered = new GuideRecord();
        registered.setId("g1");
        registered.setSessionId("s1");
        registered.setRecDeptId("d1");
        registered.setActualDeptId("d2");
        registered.setConfidence(0.876);
        registered.setTop1Hit(0);
        registered.setTop3Hit(1);
        registered.setCreatedAt(LocalDateTime.now());

        GuideRecord notRegistered = new GuideRecord();
        notRegistered.setId("g2");
        notRegistered.setSessionId("s2");
        notRegistered.setRecDeptId("d1");
        notRegistered.setConfidence(null);
        notRegistered.setLowConfidence(1);
        notRegistered.setCreatedAt(LocalDateTime.now());

        Page<GuideRecord> page = new Page<>(1, 10);
        page.setRecords(List.of(registered, notRegistered));
        page.setTotal(2);

        // 故意超过看板主诉摘要的截断长度，验证截断本身
        String longComplaint = "胸口闷，爬楼梯的时候更明显，休息一会儿能缓过来，最近这几天都是这样，"
                + "晚上躺下的时候也会觉得憋气，坐起来能好一点";

        ChatMessage first = new ChatMessage();
        first.setSessionId("s1");
        first.setContent(longComplaint);
        ChatMessage later = new ChatMessage();
        later.setSessionId("s1");
        later.setContent("补充：不咳嗽");
        ChatMessage other = new ChatMessage();
        other.setSessionId("s2");
        other.setContent("胳膊起疹子");

        Dept recDept = new Dept();
        recDept.setId("d1");
        recDept.setName("心血管内科");
        Dept actualDept = new Dept();
        actualDept.setId("d2");
        actualDept.setName("呼吸内科");

        when(guideRecordMapper.selectPage(any(), any())).thenReturn(page);
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(first, later, other));
        when(deptMapper.selectList(any())).thenReturn(List.of(recDept, actualDept));

        PageUtil result = service.records(1, 10);

        @SuppressWarnings("unchecked")
        List<DashboardDTO.RecordVO> records = (List<DashboardDTO.RecordVO>) result.getRecords();
        assertThat(records).hasSize(2);

        DashboardDTO.RecordVO vo = records.get(0);
        assertThat(vo.getComplaint()).startsWith("胸口闷，爬楼梯的时候更明显").endsWith("…")
                .hasSizeLessThan(longComplaint.length());
        assertThat(vo.getRecDept()).isEqualTo("心血管内科");
        assertThat(vo.getActualDept()).isEqualTo("呼吸内科");
        assertThat(vo.getConfidence()).isEqualTo(88);
        assertThat(vo.getResult()).isEqualTo("top-3 命中");
        assertThat(vo.getTone()).isEqualTo("plain");

        DashboardDTO.RecordVO unregistered = records.get(1);
        assertThat(unregistered.getActualDept()).isNull();
        assertThat(unregistered.getConfidence()).isNull();
        assertThat(unregistered.getResult()).isEqualTo("未反馈");
        assertThat(unregistered.getComplaint()).isEqualTo("胳膊起疹子");
    }

    @Test
    @DisplayName("没有记录时不查科室与消息，避免空 IN 查询")
    void recordsEmptyPageSkipsLookups() {
        Page<GuideRecord> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(guideRecordMapper.selectPage(any(), any())).thenReturn(page);

        PageUtil result = service.records(1, 10);

        assertThat(result.getRecords()).isEmpty();
        verify(chatMessageMapper, never()).selectList(any());
        verify(deptMapper, never()).selectList(any());
    }

    private static String weekLabel(DayOfWeek dayOfWeek) {
        return new String[]{"周一", "周二", "周三", "周四", "周五", "周六", "周日"}[dayOfWeek.getValue() - 1];
    }
}
