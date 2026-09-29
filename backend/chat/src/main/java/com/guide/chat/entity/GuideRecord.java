package com.guide.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 导诊记录（链路 A）：一条主诉一条导诊记录。
 * rec_top3 与 evidence 同为只写快照，供看板"排序精度"聚合与审核回放；
 * actual_dept_id 由挂号确认接口在 register_success 时与 top1_hit/top3_hit 同事务写入
 * （非 feedback 回写）；历史不回改。
 */
@Getter
@Setter
@TableName("guide_record")
public class GuideRecord extends BaseEntity {

    private String sessionId;

    private String recDeptId;

    private Double confidence;

    /** JSON Top3 候选快照 */
    private String recTop3;

    /** 0/1 */
    private Integer lowConfidence;

    /** 挂号确认时同事务写入 */
    private String actualDeptId;

    /** 0/1 */
    private Integer top1Hit;

    /** 0/1 */
    private Integer top3Hit;

    /** 0/1 增量标记 */
    private Integer aggregated;

    /** 所属聚合桶：归桶成功时写入；同方向可有多个桶，审核不能按方向反推成员 */
    private String bucketId;

    /** JSON 证据快照 */
    private String evidence;
}
