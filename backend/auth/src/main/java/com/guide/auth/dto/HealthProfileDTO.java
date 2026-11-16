package com.guide.auth.dto;

import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 患者健康档案 DTO（auth）。
 *
 * <p>缩表后的档案只保留「性别 / 出生年 / 过敏 / 近期用药 / 近期手术」。
 * {@code ageRange} 保留在读回体里作**只读兜底**（老用户没有出生年时展示旧年龄段，工单 03 接 UI）；
 * 既往病史（{@code history_*}）不再读、不再写——列保留只为迁移与回滚，整份覆盖写会清空它们，
 * 所以 Service 刻意不碰这三列。
 *
 * <p>读回体携带两组**编辑期规则**：{@code limits}（来自 {@code sys_config} 受管参数，前端不写死）
 * 与 {@code options}（性别下拉选项，来源是后端枚举）。这样患者端打开表单只发一次请求
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
        /** 出生年（1900–当前年）/ null（未填） */
        private Integer birthYear;
        /** 仅只读兜底：出生年缺失时展示旧年龄段（0-3 / … / 60+） */
        private String ageRange;
        /** 用药条目：{ value, startedOn, endedOn }，时间 YYYY-MM、空 = 未填 / 仍在用 */
        private List<MedicationEntry> medicationEntries;
        private String medicationOther;
        /** 过敏仍是字符串数组（没有时间语义） */
        private List<String> allergyTags;
        private String allergyOther;
        /** 手术条目：{ value, occurredOn }，时间 YYYY-MM、空 = 未填 */
        private List<SurgeryEntry> surgeryEntries;
        private String surgeryOther;
        private Limits limits;
        private Options options;
    }

    /**
     * 整份覆盖写入参：一次提交全部字段，未填即清空。
     * **不含** {@code ageRange} / {@code history_*}：前者已由出生年取代，后者只作迁移与回滚依据。
     */
    @Getter
    @Setter
    public static class ProfileSaveReq {
        private String gender;
        private Integer birthYear;
        /** 用药条目：value 非空、时间 YYYY-MM 且不得是未来月份、endedOn 不得早于 startedOn */
        private List<MedicationEntry> medicationEntries;
        private String medicationOther;
        private List<String> allergyTags;
        private String allergyOther;
        /** 手术条目：value 非空、occurredOn 为 YYYY-MM 且不得是未来月份 */
        private List<SurgeryEntry> surgeryEntries;
        private String surgeryOther;
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
        /** 旧年龄段选项：缩表后表单不再编辑它，保留给工单 03 的只读兜底 */
        private List<Option> ageRanges;
    }

    /** 单个选项（value 入库编码值，label 展示名） */
    @Getter
    @Setter
    public static class Option {
        private String value;
        private String label;
    }

    /** 标签词表项（只读列表用）：type = medication / allergy / surgery */
    @Getter
    @Setter
    public static class TagVO {
        private String id;
        private String term;
        private String type;
    }
}
