package com.guide.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.guide.auth.support.LegacyProfile;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 患者健康档案（auth）：读取自己那份、整份覆盖写、加载标签词表、注册时采集性别与出生年。
 *
 * <p>缩表后只保留「性别 / 出生年 / 过敏 / 近期用药 / 近期手术」。旧字段
 * {@code ageRange} / {@code history_*} **保留列但不再写**——整份覆盖写若把这三列一并写空，
 * 迁移与回滚的依据就没了；{@code ageRange} 仅作出生年缺失时的只读兜底。
 *
 * <p>三个口径：
 * <ul>
 *   <li><b>选填、无副作用</b>：未建档读回空档案（不写库）；一个字都不填时档案层不产生任何行为。</li>
 *   <li><b>上限在源头发现</b>：前端控件只是提示，这里按 {@code sys_config} 受管参数**二次校验**，
 *       超限直接拒绝并返回可展示错误——**绝不静默裁剪**。</li>
 *   <li><b>整份覆盖写</b>：一次提交全部可编辑字段，未填即清空。更新一律走显式的
 *       {@code LambdaUpdateWrapper.set(...)} 而非 {@code updateById}——后者会跳过实体里的 null
 *       字段（NOT_NULL 策略），想「把某列写空」会静默失败。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthProfileService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 出生年下界：再早的出生年在真实患者里不存在，多半是输入错误 */
    private static final int MIN_BIRTH_YEAR = 1900;

    /** 时间输入只接受「YYYY-MM」且月份 01–12（比宽松解析更严，避免 2024-3 这类脏值落库） */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

    private final UserHealthProfileMapper profileMapper;
    private final HealthTagMapper healthTagMapper;
    private final SysConfigService sysConfigService;

    /** 读自己那份档案（不存在返回空档案，不建行） */
    public HealthProfileDTO.ProfileVO getProfile(String userId) {
        UserHealthProfile row = loadRow(userId);
        HealthProfileAssembler.Profile profile = row == null ? emptyProfileInput() : toProfile(row);
        HealthProfileDTO.ProfileVO vo = new HealthProfileDTO.ProfileVO();
        vo.setGender(profile.genderCode());
        vo.setBirthYear(profile.birthYear());
        // 只读兜底：出生年缺失时，前端据此展示旧年龄段（不再允许编辑）
        vo.setAgeRange(profile.ageRangeCode());
        vo.setMedicationEntries(profile.medicationEntries());
        vo.setMedicationOther(profile.medicationOther());
        vo.setAllergyTags(profile.allergyTags());
        vo.setAllergyOther(profile.allergyOther());
        vo.setSurgeryEntries(profile.surgeryEntries());
        vo.setSurgeryOther(profile.surgeryOther());
        vo.setLimits(limits());
        vo.setOptions(options());
        return vo;
    }

    /**
     * 整份覆盖写：先校验、再落库。超限（标签条数 / 自由文本字数）返回可展示错误，不写库。
     *
     * <p><b>刻意不写 {@code age_range} / {@code history_tags} / {@code history_other}</b>：
     * 这三个旧列只作迁移与回滚依据（spec §2），整份覆盖会把它们清空。
     */
    @Transactional
    public void saveProfile(String userId, HealthProfileDTO.ProfileSaveReq req) {
        int tagMax = tagMax();
        int textMax = textMax();

        Gender gender = parseGender(req.getGender());
        Integer birthYear = parseBirthYear(req.getBirthYear());
        List<MedicationEntry> medicationEntries = normalizeMedicationEntries(req.getMedicationEntries(), tagMax);
        List<String> allergyTags = normalizeTags(req.getAllergyTags(), HealthTagType.ALLERGY.getLabel(), tagMax);
        List<SurgeryEntry> surgeryEntries = normalizeSurgeryEntries(req.getSurgeryEntries(), tagMax);
        String medicationOther = normalizeText(req.getMedicationOther(), HealthTagType.MEDICATION.getLabel() + "补充", textMax);
        String allergyOther = normalizeText(req.getAllergyOther(), HealthTagType.ALLERGY.getLabel() + "补充", textMax);
        String surgeryOther = normalizeText(req.getSurgeryOther(), HealthTagType.SURGERY.getLabel() + "补充", textMax);
        checkTextTotal(medicationOther, allergyOther, surgeryOther);

        // 判存在按物理行判（唯一键认物理行，不认 deleted）；没建过档就插一行
        if (profileMapper.countIncludingDeleted(userId) == 0) {
            UserHealthProfile row = new UserHealthProfile();
            row.setUserId(userId);
            row.setGender(gender);
            row.setBirthYear(birthYear);
            row.setMedicationTags(toJson(medicationEntries));
            row.setMedicationOther(medicationOther);
            row.setAllergyTags(toJson(allergyTags));
            row.setAllergyOther(allergyOther);
            row.setSurgeryTags(toJson(surgeryEntries));
            row.setSurgeryOther(surgeryOther);
            profileMapper.insert(row);
            return;
        }

        profileMapper.revive(userId); // 幂等：没被删过时影响 0 行
        profileMapper.update(null, Wrappers.<UserHealthProfile>lambdaUpdate()
                .set(UserHealthProfile::getGender, gender)
                .set(UserHealthProfile::getBirthYear, birthYear)
                .set(UserHealthProfile::getMedicationTags, toJson(medicationEntries))
                .set(UserHealthProfile::getMedicationOther, medicationOther)
                .set(UserHealthProfile::getAllergyTags, toJson(allergyTags))
                .set(UserHealthProfile::getAllergyOther, allergyOther)
                .set(UserHealthProfile::getSurgeryTags, toJson(surgeryEntries))
                .set(UserHealthProfile::getSurgeryOther, surgeryOther)
                .eq(UserHealthProfile::getUserId, userId));
    }

    /**
     * 注册时采集性别与出生年（选填，同一事务内随用户创建写入档案）。
     *
     * <p>两个都不填时**不建档**——空档案行没有意义，且会让「未建档」与「填了但全空」变得无法区分。
     * 用户是新建的，正常情况下不存在旧档案；判存在仍按物理行判（唯一键口径，见《数据库设计.md》§0.1）。
     *
     * <p>只写 {@code gender} / {@code birth_year}：档案其余字段留空，患者之后自己补。
     * 非法性别 / 出生年直接抛 {@code PARAM_INVALID}，让注册整体回滚。
     */
    @Transactional
    public void createOnRegister(String userId, String genderCode, Integer birthYear) {
        Gender gender = parseGender(genderCode);
        Integer year = parseBirthYear(birthYear);
        if (gender == null && year == null) {
            return;
        }
        if (profileMapper.countIncludingDeleted(userId) == 0) {
            UserHealthProfile row = new UserHealthProfile();
            row.setUserId(userId);
            row.setGender(gender);
            row.setBirthYear(year);
            row.setMedicationTags(toJson(List.of()));
            row.setAllergyTags(toJson(List.of()));
            row.setSurgeryTags(toJson(List.of()));
            profileMapper.insert(row);
            return;
        }
        profileMapper.revive(userId);
        profileMapper.update(null, Wrappers.<UserHealthProfile>lambdaUpdate()
                .set(UserHealthProfile::getGender, gender)
                .set(UserHealthProfile::getBirthYear, year)
                .eq(UserHealthProfile::getUserId, userId));
    }

    /**
     * 标签词表只读列表（**仅启用项**）：患者端选项加载用。
     *
     * <p>「停用只作用在入口」的入口就是这里——停用项不再出现在患者端多选列表里。
     * 管理端启停见 {@link HealthTagAdminService}；已保存档案里**已勾选的**标签照常进注入文本。
     */
    public List<HealthProfileDTO.TagVO> listEnabledTags() {
        List<HealthTag> rows = selectEnabledTags();
        List<HealthProfileDTO.TagVO> result = new ArrayList<>(rows.size());
        for (HealthTag tag : rows) {
            result.add(HealthTagView.option(tag));
        }
        return result;
    }

    /** 启用标签（词表类别序 + 词序）：患者端选项加载用 */
    private List<HealthTag> selectEnabledTags() {
        return healthTagMapper.selectList(Wrappers.<HealthTag>lambdaQuery()
                .eq(HealthTag::getEnabled, 1)
                .orderByAsc(HealthTag::getType)
                .orderByAsc(HealthTag::getTerm));
    }

    /**
     * 存量迁移读取（第一页）：见 {@link #listLegacyProfiles(int, String)}。
     *
     * <p>只读、不分页游标；迁移任务应优先用带游标的版本，否则每次重启只处理同前 N 个用户。
     */
    public List<LegacyProfile> listLegacyProfiles(int limit) {
        return listLegacyProfiles(limit, null);
    }

    /**
     * 存量迁移读取（按 {@code user_id} 游标分页）。
     *
     * <p><b>为什么提供这个入口</b>：{@code user_health_profile} 归 auth，memory 不该直接读这张表
     * （依赖方向与字段属主都要求如此）。迁移只拿旧慢病字段与用药/过敏原始 JSON，看不到其它列。
     *
     * <p>空档案也要返回：用药/过敏的字符串数组格式转换与"有没有旧慢病"无关。
     * 只读 {@code deleted=0} 的活跃行——逻辑删除的档案不该被迁回记忆。
     *
     * @param limit       本批上限（≤0 直接返回空，不发查询）
     * @param afterUserId 游标（只取 {@code user_id > afterUserId}）；null / 空串 = 第一页
     */
    public List<LegacyProfile> listLegacyProfiles(int limit, String afterUserId) {
        if (limit <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<UserHealthProfile> query = Wrappers.<UserHealthProfile>lambdaQuery()
                .select(UserHealthProfile::getUserId,
                        UserHealthProfile::getHistoryTags,
                        UserHealthProfile::getHistoryOther,
                        UserHealthProfile::getMedicationTags,
                        UserHealthProfile::getAllergyTags)
                .orderByAsc(UserHealthProfile::getUserId)
                // limit 是内部常量，不来自请求；MyBatis-Plus 的 last 不支持占位符
                .last("LIMIT " + limit);
        if (afterUserId != null && !afterUserId.isBlank()) {
            query.gt(UserHealthProfile::getUserId, afterUserId);
        }
        List<UserHealthProfile> rows = profileMapper.selectList(query);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<LegacyProfile> result = new ArrayList<>(rows.size());
        for (UserHealthProfile row : rows) {
            result.add(new LegacyProfile(row.getUserId(), parseTags(row.getHistoryTags()),
                    row.getHistoryOther(), row.getMedicationTags(), row.getAllergyTags()));
        }
        return result;
    }

    /**
     * 用药列格式迁移：老字符串数组 {@code ["二甲双胍"]} → 对象数组
     * {@code [{"value":"二甲双胍","startedOn":null,"endedOn":null}]}。
     *
     * <p><b>已是对象数组则不动</b>：迁移必须幂等，每次启动都跑也不会产生无意义的写。
     * 混合数组里的对象原样保留、字符串逐个转换；空 / 坏 JSON 不写库（读路径能容错，迁移不猜数据）。
     *
     * <p><b>过敏列刻意不动</b>：过敏没有时间语义（现设计是纯字符串数组），强行改成对象
     * 只会让读路径与前端多一层无用的兼容。
     *
     * @return true = 本次确实改写了用药列
     */
    @Transactional
    public boolean rewriteMedicationTagsAsObjects(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        UserHealthProfile row = loadRow(userId);
        if (row == null) {
            return false;
        }
        JsonNode array = readArray(row.getMedicationTags(), "用药");
        if (array == null || array.isEmpty()) {
            return false;
        }
        List<Object> rewritten = new ArrayList<>(array.size());
        boolean changed = false;
        for (JsonNode node : array) {
            if (node.isTextual()) {
                String value = node.asText().trim();
                // 空白字符串不是业务数据；跳过但标记改动，借这次迁移把脏值一并清掉
                if (!value.isEmpty()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", value);
                    entry.put("startedOn", null);
                    entry.put("endedOn", null);
                    rewritten.add(entry);
                }
                changed = true;
            } else if (node.isObject()) {
                rewritten.add(OBJECT_MAPPER.convertValue(node, Map.class));
            } else {
                // 结构外的节点原样带回，迁移不丢数据
                rewritten.add(OBJECT_MAPPER.convertValue(node, Object.class));
            }
        }
        if (!changed) {
            return false;
        }
        profileMapper.update(null, Wrappers.<UserHealthProfile>lambdaUpdate()
                .set(UserHealthProfile::getMedicationTags, toJson(rewritten))
                .eq(UserHealthProfile::getUserId, userId));
        return true;
    }

    /**
     * 为导诊链路组装档案待注入文本，并把**结构化档案**一并带回，供证据快照存
     * "系统当时看到的档案"。
     *
     * <p>档案不存在 / 全空 ⇒ 文本为空串、结构化档案为 null（上层据此不拼 prompt 段、
     * 不加推荐卡行、快照节点写空，链路行为与无档案时完全一致）。
     *
     * <p>待注入文本超 350 字天花板时**记 WARN 告警、不裁剪**——超出不是患者的问题，
     * 是配置或词表出了问题，要能在源头被发现。
     */
    public ChatProfile assembleForChat(String userId) {
        UserHealthProfile row = loadRow(userId);
        if (row == null) {
            // 无档案（大多数用户）：直接给空组装结果
            return new ChatProfile(HealthProfileAssembler.assemble(emptyProfileInput()), null);
        }
        HealthProfileAssembler.Profile profile = toProfile(row);
        HealthProfileAssembler.Assembly assembly = HealthProfileAssembler.assemble(profile);
        if (assembly.textOverflow()) {
            log.warn("健康档案提示文本超 {} 字天花板（当前 {} 字）：非患者额度，属配置/词表异常，不裁剪、请检查",
                    HealthProfileAssembler.TEXT_MAX, assembly.profileText().length());
        }
        return new ChatProfile(assembly, profile);
    }

    /**
     * 导诊链路读档结果：待注入文本 + 结构化档案（证据快照用）。
     *
     * <p>{@code structure} 是患者**当时填的结构化内容**（编码 + 标签 + 自由文本）——随结论
     * 落进证据快照的 profile 节点，供审核时看清"患者当时到底填了什么"，而不只是模型看到了什么。
     * 未建档时为 null。
     */
    public record ChatProfile(HealthProfileAssembler.Assembly assembly,
                              HealthProfileAssembler.Profile structure) {
    }

    /** 读档案物理行（不存在返回 null）：读接口与导诊链路共用的唯一入口 */
    private UserHealthProfile loadRow(String userId) {
        return profileMapper.selectOne(Wrappers.<UserHealthProfile>lambdaQuery()
                .eq(UserHealthProfile::getUserId, userId).last("LIMIT 1"));
    }

    /** 档案行 → 结构化档案（纯数据）：读接口、导诊链路、快照共用，避免各写一遍映射 */
    private HealthProfileAssembler.Profile toProfile(UserHealthProfile row) {
        return new HealthProfileAssembler.Profile(
                row.getGender() == null ? null : row.getGender().getCode(),
                row.getBirthYear(),
                row.getAgeRange(),
                parseTags(row.getHistoryTags()), row.getHistoryOther(),
                parseMedicationEntries(row.getMedicationTags()), row.getMedicationOther(),
                parseTags(row.getAllergyTags()), row.getAllergyOther(),
                parseSurgeryEntries(row.getSurgeryTags()), row.getSurgeryOther());
    }

    private HealthProfileAssembler.Profile emptyProfileInput() {
        return new HealthProfileAssembler.Profile(
                null, null, null,
                List.of(), null,
                List.of(), null,
                List.of(), null,
                List.of(), null);
    }

    /**
     * 用药条目清洗：value 非空、时间合法且不得是未来月份、结束不得早于开始、
     * 同值同时间只留一条；去重后仍超上限即拒绝。
     */
    private List<MedicationEntry> normalizeMedicationEntries(List<MedicationEntry> raw, int max) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<MedicationEntry> result = new ArrayList<>();
        for (MedicationEntry entry : raw) {
            if (entry == null) {
                continue;
            }
            String value = requireValue(entry.value(), "用药名称");
            String startedOn = normalizeMonth(entry.startedOn(), "用药开始时间");
            String endedOn = normalizeMonth(entry.endedOn(), "用药结束时间");
            if (startedOn != null && endedOn != null
                    && YearMonth.parse(endedOn, MONTH_FORMAT).isBefore(YearMonth.parse(startedOn, MONTH_FORMAT))) {
                throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                        "用药结束时间不能早于开始时间：" + value);
            }
            String key = value + "|" + startedOn + "|" + endedOn;
            if (seen.add(key)) {
                result.add(new MedicationEntry(value, startedOn, endedOn));
            }
        }
        checkEntryCount(result.size(), "用药", max);
        return List.copyOf(result);
    }

    /** 手术条目清洗：value 非空、发生时间合法且不得是未来月份、同值同时间只留一条。 */
    private List<SurgeryEntry> normalizeSurgeryEntries(List<SurgeryEntry> raw, int max) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SurgeryEntry> result = new ArrayList<>();
        for (SurgeryEntry entry : raw) {
            if (entry == null) {
                continue;
            }
            String value = requireValue(entry.value(), "手术名称");
            String occurredOn = normalizeMonth(entry.occurredOn(), "手术发生时间");
            String key = value + "|" + occurredOn;
            if (seen.add(key)) {
                result.add(new SurgeryEntry(value, occurredOn));
            }
        }
        checkEntryCount(result.size(), "手术", max);
        return List.copyOf(result);
    }

    /** 条目值去掉首尾空白后不得为空（空条目不是「未填」，而是脏输入） */
    private String requireValue(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), label + "不能为空");
        }
        return raw.trim();
    }

    /**
     * 月份校验：空 = 未填；否则必须是 {@code YYYY-MM}（01–12），且不得晚于当前月份。
     * 开始 / 结束 / 发生时间同一口径——未来日期没有医学意义，只会让「近期」判断失真。
     */
    private String normalizeMonth(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!MONTH_PATTERN.matcher(value).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    label + "格式应为 YYYY-MM（如 2024-03）");
        }
        YearMonth month = YearMonth.parse(value, MONTH_FORMAT);
        if (month.isAfter(YearMonth.now())) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), label + "不能是未来月份");
        }
        return month.format(MONTH_FORMAT);
    }

    private void checkEntryCount(int size, String label, int max) {
        if (size > max) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    label + "最多填写 " + max + " 项（当前 " + size + " 项）");
        }
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
    private void checkTextTotal(String medicationOther, String allergyOther, String surgeryOther) {
        int total = textLength(medicationOther) + textLength(allergyOther) + textLength(surgeryOther);
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

    /** 出生年校验：空 = 未填；1900–当前年，越界直接拒绝 */
    private Integer parseBirthYear(Integer birthYear) {
        if (birthYear == null) {
            return null;
        }
        int current = Year.now().getValue();
        if (birthYear < MIN_BIRTH_YEAR || birthYear > current) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "出生年需在 " + MIN_BIRTH_YEAR + " 到 " + current + " 之间");
        }
        return birthYear;
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

    /**
     * 用药列 → 条目列表，**同时兼容两种形态**：
     * <ul>
     *   <li>老字符串数组 {@code ["二甲双胍"]}（工单 02 及以前写入）⇒ 时间字段为空；</li>
     *   <li>新对象数组 {@code [{"value":"二甲双胍","startedOn":"2024-03","endedOn":null}]}。</li>
     * </ul>
     * 解析失败按空处理并记 WARN——读路径不能因一列脏 JSON 打不开档案。
     */
    private List<MedicationEntry> parseMedicationEntries(String json) {
        JsonNode array = readArray(json, "用药");
        if (array == null) {
            return List.of();
        }
        List<MedicationEntry> entries = new ArrayList<>();
        for (JsonNode node : array) {
            if (node.isTextual()) {
                // 老格式：字符串就是药名，时间未知
                if (!node.asText().isBlank()) {
                    entries.add(new MedicationEntry(node.asText().trim(), null, null));
                }
            } else if (node.isObject()) {
                String value = textOrNull(node, "value");
                if (value != null && !value.isBlank()) {
                    entries.add(new MedicationEntry(value.trim(),
                            textOrNull(node, "startedOn"), textOrNull(node, "endedOn")));
                }
            }
        }
        return List.copyOf(entries);
    }

    /** 手术列 → 条目列表，兼容老字符串数组与新对象数组（同上）。 */
    private List<SurgeryEntry> parseSurgeryEntries(String json) {
        JsonNode array = readArray(json, "手术");
        if (array == null) {
            return List.of();
        }
        List<SurgeryEntry> entries = new ArrayList<>();
        for (JsonNode node : array) {
            if (node.isTextual()) {
                if (!node.asText().isBlank()) {
                    entries.add(new SurgeryEntry(node.asText().trim(), null));
                }
            } else if (node.isObject()) {
                String value = textOrNull(node, "value");
                if (value != null && !value.isBlank()) {
                    entries.add(new SurgeryEntry(value.trim(), textOrNull(node, "occurredOn")));
                }
            }
        }
        return List.copyOf(entries);
    }

    /** 读 JSON 数组；空 / 非数组 / 坏 JSON 一律 null（调用方按空处理） */
    private JsonNode readArray(String json, String label) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (node != null && node.isArray()) {
                return node;
            }
            log.warn("健康档案{} JSON 不是数组，按空处理：{}", label, json);
            return null;
        } catch (Exception e) {
            log.warn("健康档案{} JSON 解析失败，按空处理：{}", label, json);
            return null;
        }
    }

    /** 取对象里的字符串字段；缺失 / null / 空白一律 null（空时间合法） */
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text.trim();
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            // 只可能是不可序列化的对象，字符串数组 / 条目记录不会走到这里
            throw new IllegalStateException("健康档案 JSON 序列化失败", e);
        }
    }
}
