package com.guide.stats.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据看板 DTO（管理端 Dashboard 页）。
 *
 * <p>口径字段刻意放在后端算完再下发：准确率的分母怎么定（已挂号 + 非低置信度 + 排除患者挂错）
 * 是业务口径，不能散到前端去拼；前端只负责显示与把 tone 映射成样式类。
 */
public final class DashboardDTO {

    private DashboardDTO() {
    }

    /** KPI 卡：环形 + 标签 + 数值 + 附注 */
    @Getter
    @Setter
    public static class KpiVO {
        private String label;
        /** 主数值（比率类带 %，计数类带「条」） */
        private String value;
        /** 环内文字 */
        private String ring;
        /** 环形弧长比例 0–1 */
        private Double pct;
        /** 附注色调：up / down / flat */
        private String tone;
        /** 附注文案（环比等），null 表示不显示 */
        private String note;
        private Boolean warn;
    }

    /** 每日导诊量（已反馈口径）的命中构成 */
    @Getter
    @Setter
    public static class TrendVO {
        /** yyyy-MM-dd */
        private String date;
        /** 周一…周日 */
        private String label;
        private Long hit;
        private Long miss;
    }

    /** 错误根因分布：基于 root_cause 最新归因（一条记录可标多个根因） */
    @Getter
    @Setter
    public static class CauseVO {
        private String name;
        private Long count;
        /** 占全部标注项的百分比 */
        private Double pct;
    }

    /** 看板首屏聚合 */
    @Getter
    @Setter
    public static class OverviewVO {
        private Integer days;
        private List<KpiVO> kpis;
        private List<TrendVO> trend;
        private List<CauseVO> rootCauses;
        /** 准确率口径的有效反馈样本量；0 表示还没有已确认挂号的记录 */
        private Long sampleSize;
    }

    /** 最近导诊记录行 */
    @Getter
    @Setter
    public static class RecordVO {
        private String id;
        private LocalDateTime time;
        /** 主诉摘要（该会话第一条患者消息，截断） */
        private String complaint;
        private String recDept;
        /** 置信度百分比（整数）；null = 模型未给合法值/兜底科室 */
        private Integer confidence;
        private String actualDept;
        /** 结果文案 */
        private String result;
        /** 结果色调：ok / plain / warn */
        private String tone;
    }
}
