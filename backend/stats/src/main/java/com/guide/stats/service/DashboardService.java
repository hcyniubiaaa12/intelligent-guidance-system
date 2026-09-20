package com.guide.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.util.PageUtil;
import com.guide.feedback.entity.ReviewTask;
import com.guide.feedback.entity.RootCause;
import com.guide.feedback.enums.ReviewStatus;
import com.guide.feedback.enums.RootCauseKey;
import com.guide.feedback.mapper.ReviewTaskMapper;
import com.guide.feedback.mapper.RootCauseMapper;
import com.guide.kb.entity.Dept;
import com.guide.kb.mapper.DeptMapper;
import com.guide.stats.dto.DashboardDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据看板聚合（stats，链路 C 第 ⑦⑫ 步的"看板实时反映"）。
 *
 * <p><b>准确率口径（唯一依据是行为，不是主观评价）</b>：
 * 分母 = 已确认挂号（actual_dept 非空）且非低置信度 且未被标注「患者挂错」的记录。
 * 三条排除各有出处：未挂号没有事实（患者可以不挂，不做完整性假设）；低置信度进盲区榜、
 * 不参与准确率统计；「患者挂错」是审核归因里明确的"非系统责任"。
 * 命中率一律 = 分子 / 上述分母，不做分母为 0 的除法。
 *
 * <p><b>低置信度</b>是盲区榜的读数：它只反映"系统自己没把握"，与对错无关，故单独成 KPI，
 * 不混进命中率。
 *
 * <p>跨模块只读：guide_record / chat_message（chat）、root_cause / review_task（feedback）、
 * dept（kb）。看板是纯读侧，不写任何业务表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /**
     * 根因选项里代表「非系统责任」的一项（存的是小写 key），准确率口径要排除标了它的记录。
     * 取值取自 {@link RootCauseKey}（后端唯一定义 key 与中文名的地方）——不写字面量，
     * 免得改文案时这处静默失效。
     */
    private static final String CAUSE_PATIENT_WRONG = RootCauseKey.PATIENT_WRONG.getKey();

    /** 主诉摘要截断长度（看板一行放不下整段主诉） */
    private static final int COMPLAINT_MAX = 40;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] WEEK_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final GuideRecordMapper guideRecordMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final RootCauseMapper rootCauseMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final DeptMapper deptMapper;
    private final ObjectMapper objectMapper;

    /** 看板首屏：KPI + 每日趋势 + 根因分布 */
    public DashboardDTO.OverviewVO overview(int days) {
        int window = days <= 0 ? 7 : Math.min(days, 90);
        LocalDate fromDate = LocalDate.now().minusDays(window - 1L);
        LocalDateTime from = fromDate.atStartOfDay();

        Set<String> wrongPatientIds = wrongPatientRecordIds();
        if (!wrongPatientIds.isEmpty()) {
            log.info("看板口径：排除已标「{}」的记录 {} 条", CAUSE_PATIENT_WRONG, wrongPatientIds.size());
        }

        Hit current = hits(from, null, wrongPatientIds);
        Hit previous = hits(from.minusDays(window), from, wrongPatientIds);

        DashboardDTO.OverviewVO vo = new DashboardDTO.OverviewVO();
        vo.setDays(window);
        vo.setKpis(kpis(current, previous));
        vo.setTrend(trend(fromDate, window, wrongPatientIds));
        vo.setRootCauses(rootCauses());
        vo.setSampleSize(current.total());
        log.info("看板聚合：窗口 {} 天｜有效反馈样本 {} 条（top1 命中 {} / top3 命中 {}）｜上期样本 {} 条",
                window, current.total(), current.top1(), current.top3(), previous.total());
        return vo;
    }

    /** 最近导诊记录分页（不看窗口，永远看最新） */
    public PageUtil records(long current, long size) {
        Page<GuideRecord> page = guideRecordMapper.selectPage(PageUtil.page(current, size),
                Wrappers.<GuideRecord>lambdaQuery().orderByDesc(GuideRecord::getCreatedAt));
        List<GuideRecord> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageUtil.of(page, record -> toVO(record, Map.of(), Map.of()));
        }
        Map<String, String> deptNames = deptNames(rows);
        Map<String, String> complaints = complaints(rows);
        return PageUtil.of(page, record -> toVO(record, deptNames, complaints));
    }

    // ---------------------------------------------------------------- KPI

    private List<DashboardDTO.KpiVO> kpis(Hit current, Hit previous) {
        List<DashboardDTO.KpiVO> kpis = new ArrayList<>(4);
        kpis.add(rateKpi("Top-1 命中率", current.top1(), previous.top1(), current, previous));
        kpis.add(rateKpi("Top-3 命中率", current.top3(), previous.top3(), current, previous));

        long pending = reviewTaskMapper.selectCount(Wrappers.<ReviewTask>lambdaQuery()
                .eq(ReviewTask::getStatus, ReviewStatus.PENDING));
        long reviewed = reviewTaskMapper.selectCount(Wrappers.<ReviewTask>lambdaQuery()
                .eq(ReviewTask::getStatus, ReviewStatus.DONE));
        kpis.add(countKpi("待审核", pending, pending + reviewed, "待审核队列（review_task pending）"));

        long blind = guideRecordMapper.selectCount(Wrappers.<GuideRecord>lambdaQuery()
                .eq(GuideRecord::getLowConfidence, 1));
        long allRecords = guideRecordMapper.selectCount(Wrappers.<GuideRecord>lambdaQuery());
        kpis.add(countKpi("盲区榜", blind, allRecords, "低置信度分流，不参与准确率统计"));
        return kpis;
    }

    /** 命中率 KPI：附注给环比（百分点）+ 样本量，上期无样本时明确说不可比 */
    private DashboardDTO.KpiVO rateKpi(String label, long numerator, long previousNumerator,
                                       Hit current, Hit previous) {
        double ratio = percent(numerator, current.total());
        DashboardDTO.KpiVO kpi = new DashboardDTO.KpiVO();
        kpi.setLabel(label);
        kpi.setValue(current.total() == 0 ? "—" : String.format("%.1f%%", ratio));
        kpi.setRing(current.total() == 0 ? "—" : Math.round(ratio) + "%");
        kpi.setPct(ratio / 100d);
        kpi.setWarn(false);

        if (previous.total() == 0) {
            kpi.setTone("flat");
            kpi.setNote(current.total() == 0 ? "暂无已确认挂号的记录"
                    : "样本 " + current.total() + " 条 · 上期无样本，不可比");
            return kpi;
        }
        double delta = ratio - percent(previousNumerator, previous.total());
        kpi.setTone(delta > 0.05 ? "up" : delta < -0.05 ? "down" : "flat");
        String arrow = delta > 0.05 ? "↑ " : delta < -0.05 ? "↓ " : "— ";
        kpi.setNote(String.format("%s%.1fpp 环比 · 样本 %d 条", arrow, Math.abs(delta), current.total()));
        return kpi;
    }

    /**
     * 计数类 KPI：环形比例 = 在同类总量里的占比（待审核/已完成、盲区/全部记录），
     * 不用"数字越大环越满"这种没有含义的画法。
     */
    private DashboardDTO.KpiVO countKpi(String label, long count, long base, String note) {
        DashboardDTO.KpiVO kpi = new DashboardDTO.KpiVO();
        kpi.setLabel(label);
        kpi.setValue(count + " 条");
        kpi.setRing(count > 999 ? "999+" : String.valueOf(count));
        kpi.setPct(base <= 0 ? 0d : Math.min(1d, (double) count / base));
        kpi.setTone("flat");
        kpi.setNote(note);
        kpi.setWarn(count > 0);
        return kpi;
    }

    // ---------------------------------------------------------------- 趋势

    private List<DashboardDTO.TrendVO> trend(LocalDate fromDate, int window, Set<String> excludeIds) {
        QueryWrapper<GuideRecord> wrapper = new QueryWrapper<>();
        wrapper.select("DATE(created_at) AS d",
                        "SUM(CASE WHEN top3_hit = 1 THEN 1 ELSE 0 END) AS hit",
                        "SUM(CASE WHEN top3_hit = 0 THEN 1 ELSE 0 END) AS miss")
                .isNotNull("actual_dept_id")
                .eq("low_confidence", 0)
                .ge("created_at", fromDate.atStartOfDay())
                .groupBy("DATE(created_at)")
                .orderByAsc("d");
        applyExclude(wrapper, excludeIds);

        Map<String, long[]> byDate = new HashMap<>();
        for (Map<String, Object> row : guideRecordMapper.selectMaps(wrapper)) {
            byDate.put(String.valueOf(row.get("d")), new long[]{toLong(row.get("hit")), toLong(row.get("miss"))});
        }

        // 补齐没有数据的日期：缺口不能让图表错位
        List<DashboardDTO.TrendVO> trend = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            LocalDate date = fromDate.plusDays(i);
            long[] counts = byDate.getOrDefault(date.format(DATE_FMT), new long[]{0, 0});
            DashboardDTO.TrendVO vo = new DashboardDTO.TrendVO();
            vo.setDate(date.format(DATE_FMT));
            vo.setLabel(weekLabel(date.getDayOfWeek()));
            vo.setHit(counts[0]);
            vo.setMiss(counts[1]);
            trend.add(vo);
        }
        return trend;
    }

    private static String weekLabel(DayOfWeek dayOfWeek) {
        return WEEK_LABELS[dayOfWeek.getValue() - 1];
    }

    // ---------------------------------------------------------------- 根因分布

    /**
     * 基于 root_cause 最新归因实时聚合；一条记录可标多个根因，故按"标注项"计数。
     *
     * <p>库里存的是小写 key，下发的是中文名（{@link RootCauseKey#labelOf}）；字典外的 key
     * 原样显示而不丢弃——统计可以少一行解释，不能把数据藏起来。
     *
     * <p>包级可见：作为可单测的接缝（不需要为它去凑 overview 的调用顺序）。
     */
    List<DashboardDTO.CauseVO> rootCauses() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Set<String> unknown = new TreeSet<>();
        for (RootCause row : rootCauseMapper.selectList(Wrappers.<RootCause>lambdaQuery())) {
            for (String cause : parseCauses(row.getCauses())) {
                counts.merge(cause, 1L, Long::sum);
                if (RootCauseKey.fromKey(cause).isEmpty()) {
                    unknown.add(cause);
                }
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("根因出现字典外的值（原样显示、不影响分组）：{}", unknown);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(entry -> {
                    DashboardDTO.CauseVO vo = new DashboardDTO.CauseVO();
                    vo.setName(RootCauseKey.labelOf(entry.getKey()));
                    vo.setCount(entry.getValue());
                    vo.setPct(round1(entry.getValue() * 100d / total));
                    return vo;
                })
                .toList();
    }

    /** 已标注「患者挂错」的记录 id（准确率口径要排除）；包级可见同理，便于单测 */
    Set<String> wrongPatientRecordIds() {
        Set<String> ids = new HashSet<>();
        for (RootCause row : rootCauseMapper.selectList(Wrappers.<RootCause>lambdaQuery()
                .like(RootCause::getCauses, CAUSE_PATIENT_WRONG))) {
            // LIKE 只做粗筛（JSON 串包含），命中后仍按数组元素精确判定，避免子串误伤
            if (parseCauses(row.getCauses()).contains(CAUSE_PATIENT_WRONG) && row.getRecordId() != null) {
                ids.add(row.getRecordId());
            }
        }
        return ids;
    }

    private List<String> parseCauses(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            List<String> causes = new ArrayList<>();
            if (array.isArray()) {
                for (JsonNode node : array) {
                    String text = node.asText();
                    if (text != null && !text.isBlank()) {
                        causes.add(text);
                    }
                }
            }
            return causes;
        } catch (Exception e) {
            log.warn("根因 JSON 解析失败，已跳过：{}", e.getMessage());
            return List.of();
        }
    }

    // ---------------------------------------------------------------- 记录列表

    private Map<String, String> deptNames(List<GuideRecord> rows) {
        Set<String> ids = new HashSet<>();
        for (GuideRecord row : rows) {
            if (row.getRecDeptId() != null) {
                ids.add(row.getRecDeptId());
            }
            if (row.getActualDeptId() != null) {
                ids.add(row.getActualDeptId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        // 用 mapper 直查而不是 DeptService.listEnabled()：停用科室的历史记录也要显示科室名
        for (Dept dept : deptMapper.selectList(Wrappers.<Dept>lambdaQuery().in(Dept::getId, ids))) {
            names.put(dept.getId(), dept.getName());
        }
        return names;
    }

    /** 各会话的第一条患者消息 = 主诉（一次批量查，避免逐条回表） */
    private Map<String, String> complaints(List<GuideRecord> rows) {
        Set<String> sessionIds = new HashSet<>();
        for (GuideRecord row : rows) {
            if (row.getSessionId() != null) {
                sessionIds.add(row.getSessionId());
            }
        }
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        List<ChatMessage> messages = chatMessageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .in(ChatMessage::getSessionId, sessionIds)
                .eq(ChatMessage::getRole, MessageRole.USER)
                .orderByAsc(ChatMessage::getCreatedAt)
                .orderByAsc(ChatMessage::getId));
        Map<String, String> complaints = new HashMap<>();
        for (ChatMessage message : messages) {
            complaints.putIfAbsent(message.getSessionId(), abbreviate(message.getContent(), COMPLAINT_MAX));
        }
        return complaints;
    }

    private DashboardDTO.RecordVO toVO(GuideRecord record, Map<String, String> deptNames,
                                       Map<String, String> complaints) {
        DashboardDTO.RecordVO vo = new DashboardDTO.RecordVO();
        vo.setId(record.getId());
        vo.setTime(record.getCreatedAt());
        vo.setComplaint(complaints.getOrDefault(record.getSessionId(), "—"));
        vo.setRecDept(deptNames.getOrDefault(record.getRecDeptId(), "—"));
        vo.setActualDept(record.getActualDeptId() == null ? null
                : deptNames.getOrDefault(record.getActualDeptId(), "—"));
        vo.setConfidence(record.getConfidence() == null ? null
                : (int) Math.round(record.getConfidence() * 100));

        if (record.getActualDeptId() == null) {
            vo.setResult("未反馈");
            vo.setTone("plain");
        } else if (record.getTop1Hit() != null && record.getTop1Hit() == 1) {
            vo.setResult("top-1 命中");
            vo.setTone("ok");
        } else if (record.getTop3Hit() != null && record.getTop3Hit() == 1) {
            vo.setResult("top-3 命中");
            vo.setTone("plain");
        } else {
            vo.setResult("未命中");
            vo.setTone("warn");
        }
        return vo;
    }

    // ---------------------------------------------------------------- 口径计数

    /**
     * 窗口内准确率口径的计数。
     *
     * @param toExclusive 上界（不含），null 表示不限
     * @param excludeIds  要排除的记录 id（已标「患者挂错」）；该集合只由人工归因产生，
     *                    量级很小，用 NOT IN 足够——真到上千条说明该改成 JOIN 聚合
     */
    private Hit hits(LocalDateTime from, LocalDateTime toExclusive, Set<String> excludeIds) {
        QueryWrapper<GuideRecord> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(*) AS total",
                        "SUM(CASE WHEN top1_hit = 1 THEN 1 ELSE 0 END) AS top1_hit",
                        "SUM(CASE WHEN top3_hit = 1 THEN 1 ELSE 0 END) AS top3_hit")
                .isNotNull("actual_dept_id")
                .eq("low_confidence", 0)
                .ge("created_at", from);
        if (toExclusive != null) {
            wrapper.lt("created_at", toExclusive);
        }
        applyExclude(wrapper, excludeIds);

        Map<String, Object> row = guideRecordMapper.selectMaps(wrapper).stream().findFirst().orElse(Map.of());
        return new Hit(toLong(row.get("total")), toLong(row.get("top1_hit")), toLong(row.get("top3_hit")));
    }

    private static void applyExclude(QueryWrapper<GuideRecord> wrapper, Set<String> excludeIds) {
        if (!excludeIds.isEmpty()) {
            wrapper.notIn("id", excludeIds);
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double percent(long numerator, long denominator) {
        return denominator <= 0 ? 0d : round1(numerator * 100d / denominator);
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static String abbreviate(String text, int max) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** 准确率口径的一次计数结果 */
    private record Hit(long total, long top1, long top3) {
    }
}
