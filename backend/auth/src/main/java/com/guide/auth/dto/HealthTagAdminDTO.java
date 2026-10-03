package com.guide.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 慢病标签词表管理 DTO（管理端，auth；单据 05）。
 *
 * <p><b>为什么与 {@link HealthProfileDTO.TagVO} 分开</b>：管理端列表要看到启用状态（含停用项），
 * 患者端选项（{@code /api/profile/tags}）只列启用项、不需要暴露 enabled。两者契约不同，
 * 各持一份 DTO，避免给患者端接口带上管理字段。
 */
public final class HealthTagAdminDTO {

    private HealthTagAdminDTO() {
    }

    /** 标签行（管理端列表）：带启用状态 */
    @Getter
    @Setter
    public static class TagVO {
        private String id;
        private String term;
        /** chronic / medication / allergy */
        private String type;
        /** 1 启用 / 0 停用 */
        private Integer enabled;
    }

    /** 新增标签：词 + 类别（chronic/medication/allergy），入库默认启用 */
    @Getter
    @Setter
    public static class TagAdd {
        @NotBlank(message = "标签词不能为空")
        private String term;

        @NotBlank(message = "标签类别不能为空")
        private String type;
    }
}
