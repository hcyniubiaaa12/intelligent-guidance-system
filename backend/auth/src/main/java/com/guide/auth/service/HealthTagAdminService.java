package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.dto.HealthTagAdminDTO;
import com.guide.auth.entity.HealthTag;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.mapper.HealthTagMapper;
import com.guide.auth.support.HealthTagView;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 慢病标签词表管理（管理端，auth；单据 05）。
 *
 * <p><b>核心口径——「停用只作用在入口」</b>，与既有「科室停用」同一形态：
 * <ul>
 *   <li>患者端选项加载（{@link HealthProfileService#listEnabledTags()}）**只列启用项**；
 *       停用后该标签不再出现在可选列表里；</li>
 *   <li>但**已保存档案**里的标签照常参与「档案召回串」——组装召回串用**全量**词表
 *       （见 {@link HealthProfileService#assembleForChat}，不做 enabled 过滤）。
 *       停用只是"以后不能再选"，不是"已经选过的失效"；</li>
 *   <li>启用 / 停用只翻转 {@code health_tag.enabled} 一列，**不触碰任何档案行、不回改历史导诊记录**。</li>
 * </ul>
 *
 * <p><b>为什么列表含停用</b>（照 {@code MedicalTermService.page}）：停用只是不生效，行还留着，
 * 管理端要能看到并重新启用——否则停用就是变相删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthTagAdminService {

    private final HealthTagMapper healthTagMapper;

    /** 全部标签（含停用），供管理端词表维护 */
    public List<HealthTagAdminDTO.TagVO> listAll() {
        List<HealthTag> rows = healthTagMapper.selectList(Wrappers.<HealthTag>lambdaQuery()
                .orderByAsc(HealthTag::getType)
                .orderByAsc(HealthTag::getTerm));
        List<HealthTagAdminDTO.TagVO> result = new ArrayList<>(rows.size());
        for (HealthTag tag : rows) {
            result.add(HealthTagView.adminRow(tag));
        }
        return result;
    }

    /**
     * 新增标签：默认启用。
     *
     * <p>判存在按<b>物理行</b>判（{@code uk_ht_term_type} 是物理唯一键）：本单据没有删除标签的入口，
     * 但若库中存在被逻辑删除的同名同行，按逻辑删除过滤的判重会误判为"不存在" → INSERT 撞键。
     * 故用 {@link HealthTagMapper#countIncludingDeleted} 判重，并把并发撞键兜底成可展示错误（不 500）。
     */
    public HealthTagAdminDTO.TagVO add(HealthTagAdminDTO.TagAdd req) {
        HealthTagType type = parseType(req.getType());
        String term = req.getTerm().trim();
        if (healthTagMapper.countIncludingDeleted(term, type.getCode()) > 0) {
            throw new BizException(ErrorCode.HEALTH_TAG_EXISTS);
        }
        HealthTag entity = new HealthTag();
        entity.setTerm(term);
        entity.setType(type);
        entity.setEnabled(1);
        try {
            healthTagMapper.insert(entity);
        } catch (DataIntegrityViolationException e) {
            throw new BizException(ErrorCode.HEALTH_TAG_EXISTS);
        }
        log.info("健康档案标签新增：{}（{}）", term, type.getCode());
        return HealthTagView.adminRow(entity);
    }

    /**
     * 启用 / 停用：只翻转 {@link HealthTag#getEnabled()}。
     *
     * <p>不触碰任何档案行——停用是"入口不再提供这个选项"，不是"抹掉已填的档案"。
     */
    public HealthTagAdminDTO.TagVO toggle(String id) {
        HealthTag tag = healthTagMapper.selectById(id);
        if (tag == null) {
            throw new BizException(ErrorCode.HEALTH_TAG_NOT_FOUND);
        }
        boolean enabled = !Integer.valueOf(1).equals(tag.getEnabled());
        tag.setEnabled(enabled ? 1 : 0);
        healthTagMapper.updateById(tag);
        log.info("健康档案标签「{}」已{}", tag.getTerm(), enabled ? "启用" : "停用");
        return HealthTagView.adminRow(tag);
    }

    /** 类别校验：只认 chronic/medication/allergy 编码值（不信任前端，缺省即拒绝） */
    private HealthTagType parseType(String type) {
        if (type != null && !type.isBlank()) {
            for (HealthTagType t : HealthTagType.values()) {
                if (t.getCode().equals(type.trim())) {
                    return t;
                }
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "标签类别仅支持 chronic/medication/allergy");
    }
}
