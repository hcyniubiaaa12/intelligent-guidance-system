package com.guide.auth;

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
 * 健康档案 Service 的关键逻辑：读空档案、超限拒绝、标签清洗、整份覆盖写（含清空）。
 *
 * <p>重点在**上限的二次校验**——前端控件只是提示，后端不信任它（项目惯例）。
 */
class HealthProfileServiceTest {

    private UserHealthProfileMapper profileMapper;
    private HealthTagMapper healthTagMapper;
    private SysConfigService sysConfigService;
    private HealthProfileService service;

    @BeforeAll
    static void initTableInfo() {
        // 整份覆盖写走 LambdaUpdateWrapper.set(...)，set 会立即解析列名 → 需先注册表信息
        MpTableInfoTestSupport.init(UserHealthProfile.class);
    }

    @BeforeEach
    void setUp() {
        profileMapper = mock(UserHealthProfileMapper.class);
        healthTagMapper = mock(HealthTagMapper.class);
        sysConfigService = mock(SysConfigService.class);
        // 库中缺值：回落默认（tagMax=10 / textMax=50），与代码侧默认值一致
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

    @Test
    @DisplayName("未建档：返回空档案（空标签 + null），并带上限与下拉选项")
    void emptyProfileWhenAbsent() {
        when(profileMapper.selectOne(any())).thenReturn(null);

        HealthProfileDTO.ProfileVO vo = service.getProfile("u1");

        assertThat(vo.getHistoryTags()).isEmpty();
        assertThat(vo.getMedicationTags()).isEmpty();
        assertThat(vo.getAllergyTags()).isEmpty();
        assertThat(vo.getGender()).isNull();
        assertThat(vo.getAgeRange()).isNull();
        assertThat(vo.getLimits().getTagMax()).isEqualTo(10);
        assertThat(vo.getLimits().getTextMax()).isEqualTo(50);
        assertThat(vo.getOptions().getGenders()).extracting(HealthProfileDTO.Option::getValue)
                .containsExactly("male", "female");
        assertThat(vo.getOptions().getAgeRanges()).extracting(HealthProfileDTO.Option::getValue)
                .containsExactly("0-3", "4-6", "7-14", "15-44", "45-59", "60+");
    }

    @Test
    @DisplayName("标签超条数：后端独立二次校验直接拒绝，且不写库")
    void rejectsTooManyTags() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setHistoryTags(tags(11));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("既往病史最多选择 10 项"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
        verify(profileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("自由文本超字数：拒绝且不写库")
    void rejectsTooLongText() {
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setHistoryOther("字".repeat(51));

        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", request));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("最多 50 字"));
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("非法性别 / 年龄段直接拒绝（前端下拉不该发出来，但不信任前端）")
    void rejectsInvalidEnumCodes() {
        HealthProfileDTO.ProfileSaveReq badGender = req();
        badGender.setGender("other");
        assertThrows(BizException.class, () -> service.saveProfile("u1", badGender));

        HealthProfileDTO.ProfileSaveReq badRange = req();
        badRange.setAgeRange("18");
        BizException e = assertThrows(BizException.class, () -> service.saveProfile("u1", badRange));
        assertTrue(e.getMessage().contains("年龄段取值不合法"));
    }

    @Test
    @DisplayName("首次保存：清洗标签（去空白/去重），插入一行，空文本归一为 null")
    void createsRowWithNormalizedValues() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(0);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setGender("male");
        request.setAgeRange("45-59");
        request.setHistoryTags(List.of(" 高血压 ", "糖尿病", "高血压", ""));
        request.setHistoryOther("   ");

        service.saveProfile("u1", request);

        ArgumentCaptor<UserHealthProfile> captor = ArgumentCaptor.forClass(UserHealthProfile.class);
        verify(profileMapper).insert(captor.capture());
        UserHealthProfile saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getGender()).isEqualTo(Gender.MALE);
        assertThat(saved.getAgeRange()).isEqualTo("45-59");
        assertThat(saved.getHistoryTags()).isEqualTo("[\"高血压\",\"糖尿病\"]");
        assertThat(saved.getHistoryOther()).isNull();
        verify(profileMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("已有档案：整份覆盖写走显式 set（可清空字段），不插入")
    void overwritesExistingRow() {
        when(profileMapper.countIncludingDeleted("u1")).thenReturn(1);
        HealthProfileDTO.ProfileSaveReq request = req();
        request.setMedicationTags(List.of("二甲双胍"));

        service.saveProfile("u1", request);

        verify(profileMapper).revive("u1");
        // null 字段也要写下去（整份覆盖 = 未填即清空）→ 必须走 LambdaUpdateWrapper.set，不能用 updateById
        verify(profileMapper).update(isNull(), any());
        verify(profileMapper, never()).insert(any(UserHealthProfile.class));
    }

    @Test
    @DisplayName("标签词表只读列表：只取启用项，映射出 type 编码值")
    void listsEnabledTags() {
        HealthTag chronic = new HealthTag();
        chronic.setId("t1");
        chronic.setTerm("高血压");
        chronic.setType(HealthTagType.CHRONIC);
        when(healthTagMapper.selectList(any())).thenReturn(List.of(chronic));

        List<HealthProfileDTO.TagVO> tags = service.listEnabledTags();

        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).getId()).isEqualTo("t1");
        assertThat(tags.get(0).getTerm()).isEqualTo("高血压");
        assertThat(tags.get(0).getType()).isEqualTo("chronic");
    }
}
