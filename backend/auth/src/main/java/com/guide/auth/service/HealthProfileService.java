package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.entity.HealthTag;
import com.guide.auth.entity.UserHealthProfile;
import com.guide.auth.enums.AgeRange;
import com.guide.auth.enums.Gender;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.mapper.HealthTagMapper;
import com.guide.auth.mapper.UserHealthProfileMapper;
import com.guide.auth.support.HealthProfileAssembler;
import com.guide.auth.support.HealthTagView;
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
        UserHealthProfile row = loadRow(userId);
        HealthProfileAssembler.Profile profile = row == null ? emptyProfileInput() : toProfile(row);
        HealthProfileDTO.ProfileVO vo = new HealthProfileDTO.ProfileVO();
        vo.setGender(profile.genderCode());
        vo.setAgeRange(profile.ageRangeCode());
        vo.setHistoryTags(profile.historyTags());
        vo.setHistoryOther(profile.historyOther());
        vo.setMedicationTags(profile.medicationTags());
        vo.setMedicationOther(profile.medicationOther());
        vo.setAllergyTags(profile.allergyTags());
        vo.setAllergyOther(profile.allergyOther());
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
        List<String> historyTags = normalizeTags(req.getHistoryTags(), HealthTagType.CHRONIC.getLabel(), tagMax);
        List<String> medicationTags = normalizeTags(req.getMedicationTags(), HealthTagType.MEDICATION.getLabel(), tagMax);
        List<String> allergyTags = normalizeTags(req.getAllergyTags(), HealthTagType.ALLERGY.getLabel(), tagMax);
        String historyOther = normalizeText(req.getHistoryOther(), HealthTagType.CHRONIC.getLabel() + "补充", textMax);
        String medicationOther = normalizeText(req.getMedicationOther(), HealthTagType.MEDICATION.getLabel() + "补充", textMax);
        String allergyOther = normalizeText(req.getAllergyOther(), HealthTagType.ALLERGY.getLabel() + "补充", textMax);
        checkTextTotal(historyOther, medicationOther, allergyOther);

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

    /**
     * 标签词表只读列表（**仅启用项**）：患者端选项加载用。
     *
     * <p>「停用只作用在入口」的入口就是这里——停用项不再出现在患者端多选列表里。
     * 管理端启停见 {@link HealthTagAdminService}；已保存档案里**已勾选的**标签照常进召回串
     * （标签无条件进召回，与词表无关，见 {@link HealthProfileAssembler#assemble}）。
     */
    public List<HealthProfileDTO.TagVO> listEnabledTags() {
        List<HealthTag> rows = selectEnabledTags();
        List<HealthProfileDTO.TagVO> result = new ArrayList<>(rows.size());
        for (HealthTag tag : rows) {
            result.add(HealthTagView.option(tag));
        }
        return result;
    }

    /** 启用标签（词表类别序 + 词序）：患者端选项与召回匹配词表的共用查询 */
    private List<HealthTag> selectEnabledTags() {
        return healthTagMapper.selectList(Wrappers.<HealthTag>lambdaQuery()
                .eq(HealthTag::getEnabled, 1)
                .orderByAsc(HealthTag::getType)
                .orderByAsc(HealthTag::getTerm));
    }

    /**
     * 为导诊链路组装档案两段文本（单据 02 接上"待注入文本"、03 接上"检索用串"），
     * 并把**结构化档案**一并带回，供证据快照存"系统当时看到的档案"（单据 04）。
     *
     * <p>读档案 + 启用词表 → 纯函数 {@link HealthProfileAssembler#assemble}。档案不存在 / 全空 ⇒
     * 两段均为空串、结构化档案为 null（上层据此不拼 prompt 段、不加推荐卡行、快照节点写空，
     * 链路行为与无档案时完全一致）。
     *
     * <p>待注入文本超 350 字天花板时**记 WARN 告警、不裁剪**——超出不是患者的问题，
     * 是配置或词表出了问题，要能在源头被发现（与链路 B「丢字零容忍」同一态度）。
     */
    public ChatProfile assembleForChat(String userId) {
        UserHealthProfile row = loadRow(userId);
        if (row == null) {
            // 无档案（大多数用户）：直接给空组装结果，连词表都不查
            return new ChatProfile(HealthProfileAssembler.assemble(emptyProfileInput(), Set.of()), null);
        }
        HealthProfileAssembler.Profile profile = toProfile(row);
        HealthProfileAssembler.Assembly assembly =
                HealthProfileAssembler.assemble(profile, enabledTagTerms());
        if (assembly.textOverflow()) {
            log.warn("健康档案提示文本超 {} 字天花板（当前 {} 字）：非患者额度，属配置/词表异常，不裁剪、请检查",
                    HealthProfileAssembler.TEXT_MAX, assembly.profileText().length());
        }
        return new ChatProfile(assembly, profile);
    }

    /**
     * 导诊链路读档结果：两段文本 + 结构化档案（证据快照用）。
     *
     * <p>{@code structure} 是患者**当时填的结构化内容**（编码 + 标签 + 自由文本）——随结论
     * 落进证据快照的 profile 节点，供审核时看清"患者当时到底填了什么"，而不只是模型看到了什么。
     * 未建档时为 null（快照节点写空、与今天无差异）。
     */
    public record ChatProfile(HealthProfileAssembler.Assembly assembly,
                              HealthProfileAssembler.Profile structure) {
    }

    /** 读档案物理行（不存在返回 null）：读接口与导诊链路共用的唯一入口 */
    private UserHealthProfile loadRow(String userId) {
        return profileMapper.selectOne(Wrappers.<UserHealthProfile>lambdaQuery()
                .eq(UserHealthProfile::getUserId, userId).last("LIMIT 1"));
    }

    /** 档案行 → 结构化档案（纯数据）：读接口、导诊链路、召回组装共用，避免各写一遍映射 */
    private HealthProfileAssembler.Profile toProfile(UserHealthProfile row) {
        return new HealthProfileAssembler.Profile(
                row.getGender() == null ? null : row.getGender().getCode(),
                row.getAgeRange(),
                parseTags(row.getHistoryTags()), row.getHistoryOther(),
                parseTags(row.getMedicationTags()), row.getMedicationOther(),
                parseTags(row.getAllergyTags()), row.getAllergyOther());
    }

    private HealthProfileAssembler.Profile emptyProfileInput() {
        return new HealthProfileAssembler.Profile(
                null, null, List.of(), null, List.of(), null, List.of(), null);
    }

    /**
     * **仅启用**词表（启用的慢病 / 用药 / 过敏 term 合集）：组装召回串时**自由文本**命中判定用。
     *
     * <p><b>为什么这里要过滤 {@code enabled}</b>：标签"停用"只作用在<b>入口</b>——
     * 停用后它不再是新命中的依据（停用词不再充当自由文本的召回锚点），与「科室停用只作用在入口」同一口径。
     * 但**已勾选的标签无条件进召回串**（{@link HealthProfileAssembler#assemble} 里标签不查词表），
     * 所以已保存档案里选过该标签的患者，其召回串仍含该标签——停用只影响"以后能不能再选"，
     * 不改动任何已保存档案的内容与召回。
     */
    private Set<String> enabledTagTerms() {
        Set<String> terms = new LinkedHashSet<>();
        for (HealthTag tag : selectEnabledTags()) {
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
        limits.setTextTotalMax(textTotalMax());
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

    private int textTotalMax() {
        return sysConfigService.getInt(SysConfigService.KEY_PROFILE_TEXT_TOTAL_MAX,
                SysConfigService.DEFAULT_PROFILE_TEXT_TOTAL_MAX);
    }

    /** 三框合计校验：每框都不超时不代表合计不超——总量闸单独一道，超限拒绝、绝不静默裁剪 */
    private void checkTextTotal(String historyOther, String medicationOther, String allergyOther) {
        int total = textLength(historyOther) + textLength(medicationOther) + textLength(allergyOther);
        int max = textTotalMax();
        if (total > max) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "三框自由文本合计最多 " + max + " 字（当前 " + total + " 字）");
        }
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
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
