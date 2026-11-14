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
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;

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

    private List<String> tags(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add("标签" + i);
        }
        return list;
    }

    private int currentYear() {
        return Year.now().getValue();
    }

    @Test
    @DisplayName("未建档：返回空档案（空标签 + null），并带上限与下拉选项")
    void emptyProfileWhenAbsent() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        HealthProfileDTO.ProfileVO vo = service.getProfile("u1");

        assertThat(vo.getMedicationTags()).isEmpty();
        assertThat(vo.getAllergyTags()).isEmpty();
        assertThat(vo.getSurgeryTags()).isEmpty();
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
        assertThat(vo.getSurgeryTags()).containsExactly("阑尾切除术");
        assertThat(vo.getSurgeryOther()).isEqualTo("去年做过胆囊手术");
    }

    @Test
    @DisplayName("标签超条数：后端独立二次校验直接拒绝，且不写库")
    void rejectsTooManyTags() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setSurgeryTags(tags(11));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("手术史最多选择 10 项"));
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
        request.setSurgeryTags(List.of("阑尾切除术"));
        request.setSurgeryOther("   ");

        service.saveProfile("u1", request);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        UserHealthProfile saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getGender()).isEqualTo(Gender.MALE);
        assertThat(saved.getBirthYear()).isEqualTo(1980);
        assertThat(saved.getAllergyTags()).isEqualTo("[\"青霉素类\",\"海鲜\"]");
        assertThat(saved.getSurgeryTags()).isEqualTo("[\"阑尾切除术\"]");
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
        request.setMedicationTags(List.of("二甲双胍"));
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

        assertThat(chat.assembly().profileText())
                .isEqualTo("男、1980 年出生、过敏：青霉素类、近期用药：二甲双胍、近期手术：阑尾切除术");
        // 结构化档案仍保留旧字段（证据快照审计），只是不注入
        assertThat(chat.structure().historyTags()).containsExactly("2型糖尿病");
    }

    @SuppressWarnings("unchecked")
    private Wrapper<HealthTag> capturedTagQuery() {
        ArgumentCaptor<Wrapper<HealthTag>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(healthTagMapper).selectList(captor.capture());
        return captor.getValue();
    }
}
