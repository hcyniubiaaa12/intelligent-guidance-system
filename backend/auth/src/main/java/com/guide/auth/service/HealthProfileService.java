package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.entity.HealthTag;
import com.guide.auth.entity.UserHealthProfile;
import com.guide.auth.enums.AgeRange;
import com.guide.auth.enums.Gender;
import com.guide.auth.mapper.HealthTagMapper;
import com.guide.auth.mapper.UserHealthProfileMapper;
import com.guide.auth.support.HealthProfileAssembler;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 患者健康档案（auth）：读取自己那份、整份覆盖写、加载标签词表。
 *
 * <p>三个口径：
 * <ul>
 *   <li><b>选填、无副作用</b>：未建档读回空档案（不写库）；一个字都不填时档案层不产生任何行为，
 *       导诊链路与今天完全一致。</li>
 *   <li><b>上限在源头发现</b>：前端控件只是提示，这里按 {@code sys_config} 受管参数**二次校验**，
 *       超限直接拒绝并返回可展示错误——**绝不静默裁剪**（与链路 B「丢字零容忍」同一态度）。</li>
 *   <li><b>整份覆盖写</b>：一次提交全部字段，未填即清空。更新一律走显式的
 *       {@code LambdaUpdateWrapper.set(...)} 而非 {@code updateById}——后者会跳过实体里的 null
 *       字段（NOT_NULL 策略），想「把某列写空」会静默失败。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthProfileService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserHealthProfileMapper profileMapper;
    private final HealthTagMapper healthTagMapper;
    private final SysConfigService sysConfigService;

    /** 读自己那份档案（不存在返回空档案，不建行） */
    public HealthProfileDTO.ProfileVO getProfile(String userId) {
        UserHealthProfile row = profileMapper.selectOne(Wrappers.<UserHealthProfile>lambdaQuery()
                .eq(UserHealthProfile::getUserId, userId).last("LIMIT 1"));
        HealthProfileDTO.ProfileVO vo = new HealthProfileDTO.ProfileVO();
        if (row == null) {
            vo.setHistoryTags(List.of());
            vo.setMedicationTags(List.of());
            vo.setAllergyTags(List.of());
        } else {
            vo.setGender(row.getGender() == null ? null : row.getGender().getCode());
            vo.setAgeRange(row.getAgeRange());
            vo.setHistoryTags(parseTags(row.getHistoryTags()));
            vo.setHistoryOther(row.getHistoryOther());
            vo.setMedicationTags(parseTags(row.getMedicationTags()));
            vo.setMedicationOther(row.getMedicationOther());
            vo.setAllergyTags(parseTags(row.getAllergyTags()));
            vo.setAllergyOther(row.getAllergyOther());
        }
        vo.setLimits(limits());
        vo.setOptions(options());
        return vo;
    }

    /**
     * 整份覆盖写：先校验、再落库。超限（标签条数 / 自由文本字数）返回可展示错误，不写库。
     */
    @Transactional
    public void saveProfile(String userId, HealthProfileDTO.ProfileSaveReq req) {
        int tagMax = tagMax();
        int textMax = textMax();

        Gender gender = parseGender(req.getGender());
        String ageRange = parseAgeRange(req.getAgeRange());
        List<String> historyTags = normalizeTags(req.getHistoryTags(), "既往病史", tagMax);
        List<String> medicationTags = normalizeTags(req.getMedicationTags(), "长期用药", tagMax);
        List<String> allergyTags = normalizeTags(req.getAllergyTags(), "过敏史", tagMax);
        String historyOther = normalizeText(req.getHistoryOther(), "既往病史补充", textMax);
        String medicationOther = normalizeText(req.getMedicationOther(), "长期用药补充", textMax);
        String allergyOther = normalizeText(req.getAllergyOther(), "过敏史补充", textMax);

        // 判存在按物理行判（唯一键认物理行，不认 deleted）；没建过档就插一行
        if (profileMapper.countIncludingDeleted(userId) == 0) {
            UserHealthProfile row = new UserHealthProfile();
            row.setUserId(userId);
            row.setGender(gender);
            row.setAgeRange(ageRange);
            row.setHistoryTags(toJson(historyTags));
            row.setHistoryOther(historyOther);
            row.setMedicationTags(toJson(medicationTags));
            row.setMedicationOther(medicationOther);
            row.setAllergyTags(toJson(allergyTags));
            row.setAllergyOther(allergyOther);
            profileMapper.insert(row);
            return;
        }

        profileMapper.revive(userId); // 幂等：没被删过时影响 0 行
        profileMapper.update(null, Wrappers.<UserHealthProfile>lambdaUpdate()
                .set(UserHealthProfile::getGender, gender)
                .set(UserHealthProfile::getAgeRange, ageRange)
                .set(UserHealthProfile::getHistoryTags, toJson(historyTags))
                .set(UserHealthProfile::getHistoryOther, historyOther)
                .set(UserHealthProfile::getMedicationTags, toJson(medicationTags))
                .set(UserHealthProfile::getMedicationOther, medicationOther)
                .set(UserHealthProfile::getAllergyTags, toJson(allergyTags))
                .set(UserHealthProfile::getAllergyOther, allergyOther)
                .eq(UserHealthProfile::getUserId, userId));
    }

    /** 标签词表只读列表（仅启用项）：患者端选项加载用；管理端启停第 05 单再补 */
    public List<HealthProfileDTO.TagVO> listEnabledTags() {
        List<HealthTag> rows = healthTagMapper.selectList(Wrappers.<HealthTag>lambdaQuery()
                .eq(HealthTag::getEnabled, 1)
                .orderByAsc(HealthTag::getType)
                .orderByAsc(HealthTag::getTerm));
        List<HealthProfileDTO.TagVO> result = new ArrayList<>(rows.size());
        for (HealthTag tag : rows) {
            HealthProfileDTO.TagVO vo = new HealthProfileDTO.TagVO();
            vo.setId(tag.getId());
            vo.setTerm(tag.getTerm());
            vo.setType(tag.getType().getCode());
            result.add(vo);
        }
        return result;
    }

    /**
     * 为导诊链路组装档案两段文本（单据 02 只接上"待注入文本"这一半；检索用串留给 03）。
     *
     * <p>读档案 + 启用词表 → 纯函数 {@link HealthProfileAssembler#assemble}。档案不存在 / 全空 ⇒
     * 两段均为空串（上层据此不拼 prompt 段、不加推荐卡行，链路行为与无档案时完全一致）。
     *
     * <p>待注入文本超 350 字天花板时**记 WARN 告警、不裁剪**——超出不是患者的问题，
     * 是配置或词表出了问题，要能在源头被发现（与链路 B「丢字零容忍」同一态度）。
     */
    public HealthProfileAssembler.Assembly assembleForChat(String userId) {
        UserHealthProfile row = profileMapper.selectOne(Wrappers.<UserHealthProfile>lambdaQuery()
                .eq(UserHealthProfile::getUserId, userId).last("LIMIT 1"));
        if (row == null) {
            // 无档案（大多数用户）：直接给空组装结果，连词表都不查
            return HealthProfileAssembler.assemble(emptyProfileInput(), Set.of());
        }
        HealthProfileAssembler.Profile profile = new HealthProfileAssembler.Profile(
                row.getGender() == null ? null : row.getGender().getCode(),
                row.getAgeRange(),
                parseTags(row.getHistoryTags()), row.getHistoryOther(),
                parseTags(row.getMedicationTags()), row.getMedicationOther(),
                parseTags(row.getAllergyTags()), row.getAllergyOther());
        HealthProfileAssembler.Assembly assembly =
                HealthProfileAssembler.assemble(profile, enabledTagTerms());
        if (assembly.textOverflow()) {
            log.warn("健康档案提示文本超 {} 字天花板（当前 {} 字）：非患者额度，属配置/词表异常，不裁剪、请检查",
                    HealthProfileAssembler.TEXT_MAX, assembly.text().length());
        }
        return assembly;
    }

    private HealthProfileAssembler.Profile emptyProfileInput() {
        return new HealthProfileAssembler.Profile(
                null, null, List.of(), null, List.of(), null, List.of(), null);
    }

    /** 启用词表（慢病 / 用药 / 过敏的 term 合集）：自由文本命中判定用，也是后续档案召回串的原料 */
    private Set<String> enabledTagTerms() {
        List<HealthTag> rows = healthTagMapper.selectList(Wrappers.<HealthTag>lambdaQuery()
                .eq(HealthTag::getEnabled, 1));
        Set<String> terms = new LinkedHashSet<>();
        for (HealthTag tag : rows) {
            if (tag.getTerm() != null && !tag.getTerm().isBlank()) {
                terms.add(tag.getTerm().trim());
            }
        }
        return terms;
    }


    private HealthProfileDTO.Limits limits() {
        HealthProfileDTO.Limits limits = new HealthProfileDTO.Limits();
        limits.setTagMax(tagMax());
        limits.setTextMax(textMax());
        return limits;
    }

    private int tagMax() {
        return sysConfigService.getInt(SysConfigService.KEY_PROFILE_TAG_MAX,
                SysConfigService.DEFAULT_PROFILE_TAG_MAX);
    }

    private int textMax() {
        return sysConfigService.getInt(SysConfigService.KEY_PROFILE_TEXT_MAX,
                SysConfigService.DEFAULT_PROFILE_TEXT_MAX);
    }

    private HealthProfileDTO.Options options() {
        HealthProfileDTO.Options options = new HealthProfileDTO.Options();
        List<HealthProfileDTO.Option> genders = new ArrayList<>();
        for (Gender gender : Gender.values()) {
            HealthProfileDTO.Option option = new HealthProfileDTO.Option();
            option.setValue(gender.getCode());
            option.setLabel(gender.getLabel());
            genders.add(option);
        }
        List<HealthProfileDTO.Option> ageRanges = new ArrayList<>();
        for (AgeRange range : AgeRange.values()) {
            HealthProfileDTO.Option option = new HealthProfileDTO.Option();
            option.setValue(range.getCode());
            option.setLabel(range.getLabel());
            ageRanges.add(option);
        }
        options.setGenders(genders);
        options.setAgeRanges(ageRanges);
        return options;
    }

    /** 性别编码校验：空 = 未填；非法值直接拒绝（前端下拉不该发出来，但不信任前端） */
    private Gender parseGender(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        return Arrays.stream(Gender.values())
                .filter(g -> g.getCode().equals(trimmed))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID.getCode(),
                        "性别取值不合法：" + trimmed));
    }

    /** 年龄段编码校验：空 = 未填；非法值直接拒绝 */
    private String parseAgeRange(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        if (AgeRange.findByCode(trimmed).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "年龄段取值不合法：" + trimmed);
        }
        return trimmed;
    }

    /** 标签清洗：去空白、去空串、去重（保序）；去重后仍超上限即拒绝 */
    private List<String> normalizeTags(List<String> raw, String label, int max) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : raw) {
            if (tag != null && !tag.isBlank()) {
                normalized.add(tag.trim());
            }
        }
        if (normalized.size() > max) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    label + "最多选择 " + max + " 项（当前 " + normalized.size() + " 项）");
        }
        return List.copyOf(normalized);
    }

    /** 自由文本清洗：去首尾空白；空串归一为 null（可清空）；超字数即拒绝 */
    private String normalizeText(String raw, String label, int max) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    label + "最多 " + max + " 字（当前 " + trimmed.length() + " 字）");
        }
        return trimmed;
    }

    /** JSON 数组 → 列表；解析失败按空处理（读路径不因脏数据崩，记 WARN 可追） */
    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("健康档案标签 JSON 解析失败，按空处理：{}", json);
            return List.of();
        }
    }

    private String toJson(List<String> tags) {
        try {
            return OBJECT_MAPPER.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            // 只可能是不可序列化的对象，List<String> 不会走到这里
            throw new IllegalStateException("健康档案标签序列化失败", e);
        }
    }
}
