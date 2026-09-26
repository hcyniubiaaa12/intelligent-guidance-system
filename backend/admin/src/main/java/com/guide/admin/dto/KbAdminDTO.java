package com.guide.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端知识库（链路 B）的传输对象。
 *
 * <p>进度字段的写法是刻意的：**不给前端一个 0–100% 的总进度**。解析的百分比来自外部服务、
 * 写入的计数来自自己，两段耗时差着数量级，硬拼成一根条必须拍一个分段比例，而那个比例
 * 在任何一份文档上都不会对——症状是"卡在 60% 不动"，人读成"卡死了"，
 * 于是再点一次（那正是「同一文档同时跑两次入库」的触发条件，白付一次解析费）。
 */
public final class KbAdminDTO {

    private KbAdminDTO() {
    }

    /** 上传/重新处理的结果：交回 docId 与 taskId，前者定位文档、后者定位这一次运行 */
    @Getter
    @Setter
    public static class IngestVO {
        private String docId;
        private String taskId;
    }

    /** 文档行 = 文档当前态（kb_doc） + 最近一次运行（ingest_task） */
    @Getter
    @Setter
    public static class DocVO {
        private String id;
        private String title;
        private String deptId;
        private String deptName;
        private String status;
        private String failReason;
        /** retryable / fatal —— 决定「重新处理」按钮给不给 */
        private String failType;
        /** 能不能重新处理：有原文件的才能重跑（回流容器没有） */
        private Boolean reprocessable;
        private Integer chunkTotal;
        private Integer chunkDone;
        private LocalDateTime createdAt;
        private TaskVO task;
    }

    /** 最近一次运行：阶段 + 真实进度量 */
    @Getter
    @Setter
    public static class TaskVO {
        private String taskId;
        /** parse / split / embed / done */
        private String stage;
        /** running / success / failed */
        private String status;
        /** parse 阶段 = 100（百分比口径）；embed 阶段 = 切片数；split 阶段为 null */
        private Integer total;
        private Integer done;
        private String error;
    }

    /** 切片（「查看切片」抽屉）：只看标题与正文，不改——三处存储的正文以 MySQL 为准 */
    @Getter
    @Setter
    public static class ChunkVO {
        private String id;
        private String title;
        private String content;
        private Integer seq;
    }

    /**
     * 科室蓝本行（科室蓝本 tab 与上传表单的科室选项**共用一份**）。
     *
     * <p>含停用科室——停用只在导诊入口生效，知识库该挂哪个科室与它此刻开不开诊是两件事。
     * {@code chunkCount} 是该科室下所有文档的切片总数（切片表上没有科室列，由文档归属聚合而来）。
     */
    @Getter
    @Setter
    public static class DeptVO {
        private String id;
        private String name;
        private String location;
        private String intro;
        private Integer enabled;
        private Long docCount;
        private Long chunkCount;
    }

    /** 科室蓝本编辑：改名 / 位置 / 简介 / 启停。name 必填且全库唯一（模型按名字回填科室） */
    @Getter
    @Setter
    public static class DeptUpdateReq {
        @NotBlank(message = "科室名不能为空")
        private String name;
        private String location;
        private String intro;
        private Boolean enabled;
    }

    /** 映射台账行：科室 id 已换成名字，前端不再自己查表拼名字 */
    @Getter
    @Setter
    public static class MappingVO {
        private String id;
        private String symptom;
        private String mainDeptName;
        /** 交叉科室名，顿号连接；与主科室一样只在展示层合并 */
        private String crossDeptNames;
        /** init / manual / feedback */
        private String source;
    }

    /** 术语白名单行（**含停用**：停用只是不生效，行留着可重新启用） */
    @Getter
    @Setter
    public static class TermVO {
        private String id;
        private String term;
        /** part / symptom */
        private String type;
        /** llm_extract / manual */
        private String source;
        private Integer enabled;
    }

    /** 分页结果（与其他管理端接口同一形状） */
    @Getter
    @Setter
    public static class PageVO<T> {
        private long total;
        private List<T> records;
    }
}
