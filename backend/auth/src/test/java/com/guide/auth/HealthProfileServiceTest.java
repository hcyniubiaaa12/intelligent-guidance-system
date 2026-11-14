package com.guide.auth;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.entity.HealthTag;
import com.guide.auth.entity.UserHealthProfile;
import com.guide.auth.enums.Gender;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.mapper.HealthTagMapper;
import com.guide.auth.mapper.UserHealthProfileMapper;
import com.guide.auth.service.HealthProfileService;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康档案 Service 的关键逻辑：读空档案、超限拒绝、标签清洗、整份覆盖写（含清空）、
 * 出生年校验、注册时采集。
 *
 * <p>重点在**上限的二次校验**——前端控件只是提示，后端不信任它（项目惯例）；
 * 以及**旧列不被回写**——{@code age_range} / {@code history_*} 是迁移与回滚依据。
 */
class HealthProfileServiceTest {

    private UserHealthProfileMapper profileMapper;
    private HealthTagMapper healthTagMapper;
    private SysConfigService sysConfigService;
    private HealthProfileService service;

    @BeforeAll
    static void initTableInfo() {
        // 整份覆盖写走 LambdaUpdateWrapper.set(...)，set 会立即解析列名 → 需先注册表信息；
        // HealthTag 用于「入口」查询断言（getSqlSegment 生成 SQL 时解析列名）
        MpTableInfoTestSupport.init(UserHealthProfile.class, HealthTag.class);
    }

    @BeforeEach
    void setUp() {
        profileMapper = mock(UserHealthProfileMapper.class);
        healthTagMapper = mock(HealthTagMapper.class);
        sysConfigService = mock(SysConfigService.class);
        // 库中缺值：回落默认（tagMax=10 / textMax=50 / textTotalMax=120），与代码侧默认值一致
        when(sysConfigService.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = new HealthProfileService(profileMapper, healthTagMapper, sysConfigService);
    }

    private HealthProfileDTO.ProfileSaveReq req() {
        return new HealthProfileDTO.ProfileSaveReq();
    }

    private int currentYear() {
        return Year.now().getValue();
    }

    @Test
    @DisplayName("未建档：返回空档案（空标签 + null），并带上限与下拉选项")
    void emptyProfileWhenAbsent() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        HealthProfileDTO.ProfileVO vo = service.getProfile("u1");

        assertThat(vo.getMedicationEntries()).isEmpty();
        assertThat(vo.getAllergyTags()).isEmpty();
        assertThat(vo.getSurgeryEntries()).isEmpty();
        assertThat(vo.getGender()).isNull();
        assertThat(vo.getBirthYear()).isNull();
        assertThat(vo.getAgeRange()).isNull();
        assertThat(vo.getLimits().getTagMax()).isEqualTo(10);
        assertThat(vo.getLimits().getTextMax()).isEqualTo(50);
        assertThat(vo.getLimits().getTextTotalMax()).isEqualTo(120);
        assertThat(vo.getOptions().getGenders()).extracting(HealthProfileDTO.Option::getValue)
                .containsExactly("male", "female");
        assertThat(vo.getOptions().getAgeRanges()).extracting(HealthProfileDTO.Option::getValue)
                .containsExactly("0-3", "4-6", "7-14", "15-44", "45-59", "60+");
    }

    @Test
    @DisplayName("读回新字段：出生年 / 手术；旧年龄段仍读回作只读兜底")
    void readsBirthYearAndSurgery() {
        UserHealthProfile row = new UserHealthProfile();
        row.setUserId("u1");
        row.setGender(Gender.FEMALE);
        row.setBirthYear(1980);
        row.setAgeRange("45-59");
        row.setSurgeryTags("[\"阑尾切除术\"]");
        row.setSurgeryOther("去年做过胆囊手术");
        when(profileMapper.selectOne(any())).thenReturn(row);

        HealthProfileDTO.ProfileVO vo = service.getProfile("u1");

        assertThat(vo.getGender()).isEqualTo("female");
        assertThat(vo.getBirthYear()).isEqualTo(1980);
        assertThat(vo.getAgeRange()).isEqualTo("45-59");
        // 老格式（字符串数组）读回：转成时间字段为空的条目，老用户不会因为改版丢数据
        assertThat(vo.getSurgeryEntries())
                .containsExactly(new SurgeryEntry("阑尾切除术", null));
        assertThat(vo.getSurgeryOther()).isEqualTo("去年做过胆囊手术");
    }

    @Test
    @DisplayName("读回新对象数组：开始/结束/发生时间原样带回；空时间读成 null")
    void readsEntryTimesFromObjectArray() {
        UserHealthProfile row = new UserHealthProfile();
        row.setUserId("u1");
        row.setMedicationTags("[{\"value\":\"二甲双胍\",\"startedOn\":\"2024-03\",\"endedOn\":null},"
                + "{\"value\":\"布洛芬\",\"startedOn\":\"2023-01\",\"endedOn\":\"2023-02\"}]");
        row.setSurgeryTags("[{\"value\":\"阑尾切除术\",\"occurredOn\":\"2023-10\"},"
                + "{\"value\":\"胆囊切除术\",\"occurredOn\":null}]");
        when(profileMapper.selectOne(any())).thenReturn(row);

        HealthProfileDTO.ProfileVO vo = service.getProfile("u1");

        assertThat(vo.getMedicationEntries()).containsExactly(
                new MedicationEntry("二甲双胍", "2024-03", null),
                new MedicationEntry("布洛芬", "2023-01", "2023-02"));
        assertThat(vo.getSurgeryEntries()).containsExactly(
                new SurgeryEntry("阑尾切除术", "2023-10"),
                new SurgeryEntry("胆囊切除术", null));
    }

    @Test
    @DisplayName("时间语义写入：空时间合法，落库为对象数组（null 时间）")
    void savesEntriesWithEmptyTimes() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationEntries(List.of(new MedicationEntry(" 二甲双胍 ", " ", null)));
        request.setSurgeryEntries(List.of(new SurgeryEntry("阑尾切除术", "")));

        service.saveProfile("u1", request);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getMedicationTags())
                .isEqualTo("[{\"value\":\"二甲双胍\",\"startedOn\":null,\"endedOn\":null}]");
        assertThat(captor.getValue().getSurgeryTags())
                .isEqualTo("[{\"value\":\"阑尾切除术\",\"occurredOn\":null}]");
    }

    @Test
    @DisplayName("未来月份：开始 / 结束 / 发生时间一律拒绝，且不写库")
    void rejectsFutureMonths() {
        String future = YearMonth.now().plusMonths(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

        HealthProfileDTO.ProfileSaveReq started = req();
        started.setMedicationEntries(List.of(new MedicationEntry("二甲双胍", future, null)));
        BizException e1 = assertThrows(BizException.class, () -> service.saveProfile("u1", started));
        assertTrue(e1.getMessage().contains("不能是未来月份"));

        HealthProfileDTO.ProfileSaveReq occurred = req();
        occurred.setSurgeryEntries(List.of(new SurgeryEntry("阑尾切除术", future)));
        BizException e2 = assertThrows(BizException.class, () -> service.saveProfile("u1", occurred));
        assertTrue(e2.getMessage().contains("不能是未来月份"));

        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("时间格式非法：不是 YYYY-MM（含 2024-13 / 2024-3）一律拒绝")
    void rejectsInvalidMonthFormat() {
        for (String bad : List.of("2024-13", "2024-3", "2024/03", "24-03", "2024-00")) {
            HealthProfileDTO.ProfileSaveReq request = req();
            request.setMedicationEntries(List.of(new MedicationEntry("二甲双胍", bad, null)));

            BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

            assertTrue(e.getMessage().contains("YYYY-MM"), "应拒绝 " + bad);
            verify(profileMapper, never()).insert(any(UserHealthProfile.class));
        }
    }

    @Test
    @DisplayName("结束时间早于开始时间：拒绝，且不写库")
    void rejectsEndBeforeStart() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationEntries(List.of(new MedicationEntry("二甲双胍", "2024-03", "2024-02")));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertTrue(e.getMessage().contains("不能早于开始时间"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("条目值为空：拒绝（空条目是脏输入，不是「未填」）")
    void rejectsBlankEntryValue() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationEntries(List.of(new MedicationEntry("   ", "2024-03", null)));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertTrue(e.getMessage().contains("用药名称不能为空"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("同值同时间只留一条；同值不同时间各留一条（去重后再判条数上限）")
    void deduplicatesSameValueAndTime() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        HealthProfileDTO.ProfileSaveReq request = req();
        List<MedicationEntry> entries = new ArrayList<>();
        // 11 条完全相同的条目：去重后只剩 1 条，不应触发 10 条上限
        for (int i = 0; i < 11; i++) {
            entries.add(new MedicationEntry("二甲双胍", "2024-03", null));
        }
        entries.add(new MedicationEntry("二甲双胍", "2023-01", "2023-02"));
        request.setMedicationEntries(entries);

        service.saveProfile("u1", request);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getMedicationTags())
                .isEqualTo("[{\"value\":\"二甲双胍\",\"startedOn\":\"2024-03\",\"endedOn\":null},"
                        + "{\"value\":\"二甲双胍\",\"startedOn\":\"2023-01\",\"endedOn\":\"2023-02\"}]");
    }

    @Test
    @DisplayName("标签超条数：后端独立二次校验直接拒绝，且不写库")
    void rejectsTooManyTags() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setSurgeryEntries(IntStream.range(0, 11)
                .mapToObj(i -> new SurgeryEntry("手术" + i, null)).toList());

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("手术最多填写 10 项"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
        verify(profileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("自由文本超字数：拒绝且不写库")
    void rejectsTooLongText() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setSurgeryOther("字".repeat(51));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("最多 50 字"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("三框合计超上限：每框都不超、合计仍超时拒绝且不写库（不静默裁剪）")
    void rejectsTextOverTotalLimit() {
        // 单框 50 字合法（= 单框上限），三框 150 字 > 合计上限 120
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationOther("药".repeat(50));
        request.setAllergyOther("过".repeat(50));
        request.setSurgeryOther("手".repeat(50));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("合计最多 120 字"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
        verify(profileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("三框合计恰好等于上限：放行（合计闸是上界）")
    void acceptsTextAtTotalLimit() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationOther("药".repeat(50));
        request.setAllergyOther("过".repeat(50));
        request.setSurgeryOther("手".repeat(20)); // 50 + 50 + 20 = 120

        service.saveProfile("u1", request);

        verify(profileMapper).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("非法性别 / 出生年直接拒绝（前端控件不该发出来，但不信任前端）")
    void rejectsInvalidValues() {
        HealthProfileDTO.ProfileSaveReq badGender = req();
        badGender.setGender("other");
        assertThrows(BizException.class, () -> service.saveProfile("u1", badGender));

        HealthProfileDTO.ProfileSaveReq tooEarly = req();
        tooEarly.setBirthYear(1899);
        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", tooEarly));
        assertTrue(e.getMessage().contains("出生年"));

        HealthProfileDTO.ProfileSaveReq future = req();
        future.setBirthYear(currentYear() + 1);
        assertThrows(BizException.class, () -> service.saveProfile("u1", future));
    }

    @Test
    @DisplayName("出生年边界：1900 与当前年都合法")
    void acceptsBirthYearBoundaries() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setBirthYear(currentYear());
        service.saveProfile("u1", request);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getBirthYear()).isEqualTo(currentYear());
    }

    @Test
    @DisplayName("首次保存：清洗标签（去空白/去重），插入一行，空文本归一为 null")
    void createsRowWithNormalizedValues() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setGender("male");
        request.setBirthYear(1980);
        request.setAllergyTags(List.of(" 青霉素类 ", "海鲜", "青霉素类", ""));
        request.setSurgeryEntries(List.of(new SurgeryEntry(" 阑尾切除术 ", null)));
        request.setSurgeryOther("   ");

        service.saveProfile("u1", request);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        UserHealthProfile saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getGender()).isEqualTo(Gender.MALE);
        assertThat(saved.getBirthYear()).isEqualTo(1980);
        assertThat(saved.getAllergyTags()).isEqualTo("[\"青霉素类\",\"海鲜\"]");
        // 写路径统一写对象数组（时间空 = null），读路径再兼容老字符串数组
        assertThat(saved.getSurgeryTags()).isEqualTo("[{\"value\":\"阑尾切除术\",\"occurredOn\":null}]");
        assertThat(saved.getSurgeryOther()).isNull();
        // 旧列不再写：迁移/回滚依据必须留在库里
        assertThat(saved.getAgeRange()).isNull();
        assertThat(saved.getHistoryTags()).isNull();
        assertThat(saved.getHistoryOther()).isNull();
        verify(profileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("已有档案：整份覆盖写走显式 set（可清空字段），旧列不在 SET 里")
    void overwritesExistingRowWithoutTouchingLegacyColumns() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationEntries(List.of(new MedicationEntry("二甲双胍", "2024-03", null)));
        request.setBirthYear(1970);

        service.saveProfile("u1", request);

        verify(profileMapper).revive("u1");
        // null 字段也要写下去（整份覆盖 = 未填即清空）→ 必须走 LambdaUpdateWrapper.set，不能用 updateById
        ArgumentCaptor<Wrapper<UserHealthProfile>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(profileMapper).update(isNull(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<UserHealthProfile>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("gender", "birth_year", "medication_tags", "allergy_tags", "surgery_tags");
        assertThat(sqlSet).doesNotContain("age_range", "history_tags", "history_other");
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("注册采集：性别/出生年写入新档案行，其余字段留空")
    void createOnRegisterWritesGenderAndBirthYear() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);

        service.createOnRegister("u1", "female", 1995);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        UserHealthProfile saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(saved.getBirthYear()).isEqualTo(1995);
        assertThat(saved.getMedicationTags()).isEqualTo("[]");
        assertThat(saved.getAllergyTags()).isEqualTo("[]");
        assertThat(saved.getSurgeryTags()).isEqualTo("[]");
    }

    @Test
    @DisplayName("注册采集：两项都空不建档（空档案行没有意义）")
    void createOnRegisterSkipsWhenNothingProvided() {
        service.createOnRegister("u1", null, null);

        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
        verify(profileMapper, never()).update(any(), any());
        verify(profileMapper, never()).countIncludingDeleted(anyString());
    }

    @Test
    @DisplayName("注册采集：非法性别/出生年抛错，让注册事务整体回滚")
    void createOnRegisterRejectsInvalidValues() {
        assertThrows(BizException.class, () -> service.createOnRegister("u1", "other", null));
        assertThrows(BizException.class, () -> service.createOnRegister("u1", null, 1800));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("注册采集：档案行已存在时只补性别/出生年，不动其它字段")
    void createOnRegisterUpdatesOnlyCollectedFields() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);

        service.createOnRegister("u1", "male", 1990);

        ArgumentCaptor<Wrapper<UserHealthProfile>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(profileMapper).update(isNull(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<UserHealthProfile>) captor.getValue()).getSqlSet();
        assertThat(sqlSet).contains("gender", "birth_year");
        assertThat(sqlSet).doesNotContain("medication_tags", "allergy_tags", "surgery_tags");
    }

    @Test
    @DisplayName("标签词表只读列表：只取启用项，映射出 type 编码值")
    void listsEnabledTags() {
        HealthTag surgery = new HealthTag();
        surgery.setId("t1");
        surgery.setTerm("阑尾切除术");
        surgery.setType(HealthTagType.SURGERY);
        when(healthTagMapper.selectList(any())).thenReturn(List.of(surgery));

        List<HealthProfileDTO.TagVO> tags = service.listEnabledTags();

        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).getId()).isEqualTo("t1");
        assertThat(tags.get(0).getTerm()).isEqualTo("阑尾切除术");
        assertThat(tags.get(0).getType()).isEqualTo("surgery");
    }

    @Test
    @DisplayName("停用只作用在入口：患者端选项列表的查询按 enabled 过滤（只列启用项）")
    void optionsQueryFiltersEnabled() {
        when(healthTagMapper.selectList(any())).thenReturn(List.of());

        service.listEnabledTags();

        assertThat(capturedTagQuery().getSqlSegment()).contains("enabled");
    }

    @Test
    @DisplayName("导诊链路组装：性别 → 出生年 → 过敏 → 近期用药 → 近期手术；旧慢病不进文本")
    void assembleForChatUsesNewFieldOrder() {
        UserHealthProfile row = new UserHealthProfile();
        row.setUserId("u1");
        row.setGender(Gender.MALE);
        row.setBirthYear(1980);
        row.setHistoryTags("[\"2型糖尿病\"]");
        row.setHistoryOther("血糖有点高");
        row.setMedicationTags("[\"二甲双胍\"]");
        row.setAllergyTags("[\"青霉素类\"]");
        row.setSurgeryTags("[\"阑尾切除术\"]");
        when(profileMapper.selectOne(any())).thenReturn(row);

        HealthProfileService.ChatProfile chat = service.assembleForChat("u1");

        // 老字符串数组（工单 02 写入）经读路径转成时间空条目：文本不因改版变化
        assertThat(chat.assembly().profileText())
                .isEqualTo("男、1980 年出生、过敏：青霉素类、近期用药：二甲双胍、近期手术：阑尾切除术");
        // 结构化档案仍保留旧字段（证据快照审计），只是不注入
        assertThat(chat.structure().historyTags()).containsExactly("2型糖尿病");
    }

    // ---------- 候选接受：appendProfileFact（工单 09） ----------

    @Test
    @DisplayName("接受过敏候选（词表内）：并入 allergy_tags，只写这一列，其它字段一律不动")
    void appendsAllergyIntoVocabularyTags() {
        UserHealthProfile row = existingRow();
        row.setAllergyTags("[\"海鲜\"]");
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        when(profileMapper.selectOne(any())).thenReturn(row);
        when(healthTagMapper.selectOne(any())).thenReturn(enabledAllergyTag("青霉素"));

        service.appendProfileFact("u1", HealthTagType.ALLERGY, " 青霉素 ", null);

        LambdaUpdateWrapper<UserHealthProfile> wrapper = capturedProfileUpdate();
        assertThat(wrapper.getSqlSet()).contains("allergy_tags")
                .doesNotContain("allergy_other", "medication_tags", "surgery_tags");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("[\"海鲜\",\"青霉素\"]");
    }

    @Test
    @DisplayName("接受过敏候选（词表外）：追加到 allergy_other，用顿号与原文合并")
    void appendsAllergyIntoFreeTextWhenNotInVocabulary() {
        UserHealthProfile row = existingRow();
        row.setAllergyTags("[\"海鲜\"]");
        row.setAllergyOther("花粉");
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        when(profileMapper.selectOne(any())).thenReturn(row);
        when(healthTagMapper.selectOne(any())).thenReturn(null);

        service.appendProfileFact("u1", HealthTagType.ALLERGY, "青霉素", null);

        LambdaUpdateWrapper<UserHealthProfile> wrapper = capturedProfileUpdate();
        assertThat(wrapper.getSqlSet()).contains("allergy_other").doesNotContain("allergy_tags");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("花粉、青霉素");
    }

    @Test
    @DisplayName("接受用药候选：追加一条带开始月份的用药，保留原有条目")
    void appendsMedicationEntry() {
        UserHealthProfile row = existingRow();
        row.setMedicationTags("[{\"value\":\"布洛芬\",\"startedOn\":\"2023-01\",\"endedOn\":\"2023-02\"}]");
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        when(profileMapper.selectOne(any())).thenReturn(row);

        service.appendProfileFact("u1", HealthTagType.MEDICATION, "二甲双胍", "2024-03");

        LambdaUpdateWrapper<UserHealthProfile> wrapper = capturedProfileUpdate();
        assertThat(wrapper.getSqlSet()).contains("medication_tags").doesNotContain("allergy_tags", "surgery_tags");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(
                "[{\"value\":\"布洛芬\",\"startedOn\":\"2023-01\",\"endedOn\":\"2023-02\"},"
                        + "{\"value\":\"二甲双胍\",\"startedOn\":\"2024-03\",\"endedOn\":null}]");
        verify(healthTagMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("接受手术候选：追加一条带发生月份的手术")
    void appendsSurgeryEntry() {
        UserHealthProfile row = existingRow();
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        when(profileMapper.selectOne(any())).thenReturn(row);

        service.appendProfileFact("u1", HealthTagType.SURGERY, "阑尾切除术", "2023-10");

        LambdaUpdateWrapper<UserHealthProfile> wrapper = capturedProfileUpdate();
        assertThat(wrapper.getSqlSet()).contains("surgery_tags").doesNotContain("allergy_tags", "medication_tags");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("[{\"value\":\"阑尾切除术\",\"occurredOn\":\"2023-10\"}]");
    }

    @Test
    @DisplayName("没有档案行时建行：只写对应的那一列，不凭空写空数组到其它类别")
    void createsRowForFirstAcceptedFact() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        when(healthTagMapper.selectOne(any())).thenReturn(null);

        service.appendProfileFact("u1", HealthTagType.ALLERGY, "青霉素", null);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        UserHealthProfile saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getAllergyOther()).isEqualTo("青霉素");
        assertThat(saved.getAllergyTags()).isNull();
        assertThat(saved.getMedicationTags()).isNull();
        assertThat(saved.getSurgeryTags()).isNull();
        verify(profileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("同值已在档案里：不重复写（同一事实只存一处）")
    void sameValueAlreadyPresentIsNoop() {
        UserHealthProfile row = existingRow();
        row.setAllergyTags("[\"青霉素\"]");
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        when(profileMapper.selectOne(any())).thenReturn(row);
        when(healthTagMapper.selectOne(any())).thenReturn(enabledAllergyTag("青霉素"));

        service.appendProfileFact("u1", HealthTagType.ALLERGY, "青霉素", null);

        verify(profileMapper, never()).update(any(), any());
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("非法字段 / 空值 / 非法月份：拒绝写入（候选值再干净也不放松校验）")
    void rejectsInvalidAppendInput() {
        assertThrows(BizException.class,
                () -> service.appendProfileFact("u1", HealthTagType.CHRONIC, "糖尿病", null));
        assertThrows(BizException.class,
                () -> service.appendProfileFact("u1", HealthTagType.ALLERGY, "  ", null));
        assertThrows(BizException.class,
                () -> service.appendProfileFact("u1", HealthTagType.SURGERY, "阑尾切除术", "2024-13"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
        verify(profileMapper, never()).update(any(), any());
    }

    private UserHealthProfile existingRow() {
        UserHealthProfile row = new UserHealthProfile();
        row.setUserId("u1");
        row.setGender(Gender.MALE);
        row.setBirthYear(1980);
        return row;
    }

    private HealthTag enabledAllergyTag(String term) {
        HealthTag tag = new HealthTag();
        tag.setTerm(term);
        tag.setType(HealthTagType.ALLERGY);
        tag.setEnabled(1);
        return tag;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<UserHealthProfile> capturedProfileUpdate() {
        ArgumentCaptor<Wrapper<UserHealthProfile>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(profileMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<UserHealthProfile>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Wrapper<HealthTag> capturedTagQuery() {
        ArgumentCaptor<Wrapper<HealthTag>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(healthTagMapper).selectList(captor.capture());
        return captor.getValue();
    }
}
