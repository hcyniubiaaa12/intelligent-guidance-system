package com.guide.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 管理端运行时参数 DTO（LLM 配置页的「检索与聚合参数」面板）。
 * 参数项集合由代码侧白名单决定（类型与取值范围），前端只做展示与提交。
 */
public final class SysConfigAdminDTO {

    private SysConfigAdminDTO() {
    }

    /** 参数行：key 原样回传，供保存时定位 */
    @Getter
    @Setter
    public static class ParamVO {
        private String key;
        /** 展示名（前端面板 label） */
        private String label;
        /** 当前生效值 */
        private String value;
        /** 代码侧默认值（值缺失时的兜底，也是「重置」的依据） */
        private String defaultValue;
        /** int / decimal / bool —— 前端据此选控件与校验 */
        private String type;
        /** 取值范围（int/decimal 有；bool 为空） */
        private String range;
        /** 口径说明 */
        private String remark;
    }

    /** 单项参数提交 */
    @Getter
    @Setter
    public static class ParamItem {
        @NotBlank(message = "配置项 key 不能为空")
        private String key;

        /** 值以字符串提交，后端按白名单里的类型解析校验 */
        private String value;
    }

    /** 批量保存入参 */
    @Getter
    @Setter
    public static class ParamUpdate {
        @NotEmpty(message = "没有需要保存的参数")
        private List<ParamItem> items;
    }
}
