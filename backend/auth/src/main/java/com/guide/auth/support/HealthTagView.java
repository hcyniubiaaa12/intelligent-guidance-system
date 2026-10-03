package com.guide.auth.support;

import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.dto.HealthTagAdminDTO;
import com.guide.auth.entity.HealthTag;

/**
 * 慢病标签 → DTO 的**唯一转换处**。
 *
 * <p>患者端选项（{@link HealthProfileDTO.TagVO}，无 enabled）与管理端列表
 * （{@link HealthTagAdminDTO.TagVO}，含 enabled）契约不同、各持一份 DTO，
 * 但「从 {@link HealthTag} 读出 id / term / type」这件事只有一份实现——{@link #common}。
 * 两个 VO 都从它派生，字段口径不会各写各的。
 */
public final class HealthTagView {

    private HealthTagView() {
    }

    /** 标签 → 患者端选项 VO（无 enabled：患者端拿到的都是启用项） */
    public static HealthProfileDTO.TagVO option(HealthTag tag) {
        Common common = common(tag);
        HealthProfileDTO.TagVO vo = new HealthProfileDTO.TagVO();
        vo.setId(common.id());
        vo.setTerm(common.term());
        vo.setType(common.type());
        return vo;
    }

    /** 标签 → 管理端列表 VO（含启用状态） */
    public static HealthTagAdminDTO.TagVO adminRow(HealthTag tag) {
        Common common = common(tag);
        HealthTagAdminDTO.TagVO vo = new HealthTagAdminDTO.TagVO();
        vo.setId(common.id());
        vo.setTerm(common.term());
        vo.setType(common.type());
        vo.setEnabled(tag.getEnabled());
        return vo;
    }

    /** 两个 VO 共用的三个字段只在这里读一次（type 一律取入库编码值） */
    private static Common common(HealthTag tag) {
        return new Common(tag.getId(), tag.getTerm(), tag.getType().getCode());
    }

    private record Common(String id, String term, String type) {
    }
}
