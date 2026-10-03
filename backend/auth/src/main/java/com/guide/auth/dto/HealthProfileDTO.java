package com.guide.auth.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 患者健康档案 DTO（auth）。
 *
 * <p>读回体携带两组**编辑期规则**：{@code limits}（来自 {@code sys_config} 受管参数，前端不写死）
 * 与 {@code options}（性别 / 年龄段下拉选项，来源是后端枚举）。这样患者端打开表单只发一次请求
 * 就拿到「内容 + 规则 + 选项」，不会出现页面按旧上限拦人、后端按新上限拒绝的错位。
 */
public final class HealthProfileDTO {

    private HealthProfileDTO() {
    }

    /** 读回：档案内容 + 编辑规则 + 下拉选项（档案不存在时返回空档案，不是 404） */
    @Getter
    @Setter
    public static class ProfileVO {
        /** male / female / null（未填） */
        private String gender;
        /** 0-3 / 4-6 / 7-14 / 15-44 / 45-59 / 60+ / null */
        private String ageRange;
        private List<String> historyTags;
        private String historyOther;
        private List<String> medicationTags;
        private String medicationOther;
        private List<String> allergyTags;
        private String allergyOther;
        private Limits limits;
        private Options options;
    }

    /** 整份覆盖写入参：一次提交全部字段，未填即清空。上限由后端按 sys_config 二次校验。 */
    @Getter
    @Setter
    public static class ProfileSaveReq {
        private String gender;
        private String ageRange;
        private List<String> historyTags;
        private String historyOther;
        private List<String> medicationTags;
        private String medicationOther;
        private List<String> allergyTags;
        private String allergyOther;
    }

    /** 编辑期上限（受管参数，改完立即生效） */
    @Getter
    @Setter
    public static class Limits {
        /** 每类标签最多可选条数 */
        private int tagMax;
        /** 每个「其他」自由文本框最多字数 */
        private int textMax;
        /** 三个「其他」自由文本框合计最多字数（每框上限之外的总量闸） */
        private int textTotalMax;
    }

    /** 下拉选项 */
    @Getter
    @Setter
    public static class Options {
        private List<Option> genders;
        private List<Option> ageRanges;
    }

    /** 单个选项（value 入库编码值，label 展示名） */
    @Getter
    @Setter
    public static class Option {
        private String value;
        private String label;
    }

    /** 标签词表项（只读列表用）：type = chronic / medication / allergy */
    @Getter
    @Setter
    public static class TagVO {
        private String id;
        private String term;
        private String type;
    }
}
