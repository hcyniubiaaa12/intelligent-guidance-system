package com.guide.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核队列（链路 C）的传输对象。
 *
 * <p>科室一律下发名字：页面上出现科室 id，管理员无法判断自己在审什么。
 * 停用状态单独给一个布尔，选择器要包含停用科室并标出来。
 */
public final class ReviewAdminDTO {

    private ReviewAdminDTO() {
    }

    /** 待审桶一行 */
    @Getter
    @Setter
    public static class BucketVO {
        private String id;
        private String recDeptId;
        private String recDeptName;
        private String actualDeptId;
        private String actualDeptName;
        private String anchorText;
        private Integer count;
        /** monitoring / pending / approved / rejected / dismissed */
        private String status;
        private LocalDateTime createdAt;
    }

    /** 桶详情：桶 + 最近的代表样本 + 交叉科室预填 */
    @Getter
    @Setter
    public static class BucketDetailVO {
        private BucketVO bucket;
        /** 预填 = 桶方向 {推荐, 实际} − {主科室}；主科室还没选时按「实际科室」预填 */
        private String suggestedMainDeptId;
        private List<String> suggestedCrossDeptIds;
        private List<SampleVO> samples;
    }

    /** 代表样本：主诉原文 + 当时的证据快照 + 当前根因 */
    @Getter
    @Setter
    public static class SampleVO {
        private String recordId;
        private String symptom;
        /** guide_record.evidence 原文（JSON），前端只读展示 */
        private String evidence;
        /** 根因 key；展示名由字典接口给，不在这里重复 */
        private List<String> causes;
    }

    /** 根因字典一项：key 入库，label 显示 */
    @Getter
    @Setter
    public static class CauseOptionVO {
        private String key;
        private String label;
    }

    /** 科室选项：含停用科室，停用只是打标，不从选择器里拿掉 */
    @Getter
    @Setter
    public static class DeptOptionVO {
        private String id;
        private String name;
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class PageVO<T> {
        private long total;
        private List<T> records;
    }

    @Getter
    @Setter
    public static class CausesRequest {
        private List<String> causes;
    }

    /** 预览：主科室必填，交叉科室可空（空则用预填） */
    @Getter
    @Setter
    public static class PreviewRequest {
        private String mainDeptId;
        private List<String> crossDeptIds;
    }

    @Getter
    @Setter
    public static class PreviewVO {
        private String text;
    }

    /** 确认：文本是管理员定稿后的，不是预览接口的返回值再传一遍 */
    @Getter
    @Setter
    public static class ApproveRequest {
        private String mainDeptId;
        private List<String> crossDeptIds;
        private String syntheticText;
    }
}
