package com.guide.async.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.async.enums.IngestStage;
import com.guide.async.enums.IngestStatus;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 离线入库任务（链路 B 流水线）：任务表是权威状态；幂等与重试以 taskId 为键。
 */
@Getter
@Setter
@TableName("ingest_task")
public class IngestTask extends BaseEntity {

    private String docId;

    /**
     * 外部解析服务（DocumentMind）的任务号。
     * 两段式编排靠它：线程池只提交，定时任务扫 running 的 parse 任务去轮询，
     * 进程重启后接着轮询靠的就是它（这个值推不出来，丢了就接不上）。仅解析阶段有值。
     */
    private String externalJobId;

    private IngestStage stage;

    private IngestStatus status;

    private Integer total;

    private Integer done;

    private String error;
}
