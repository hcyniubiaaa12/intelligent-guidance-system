package com.guide.auth;

import com.guide.auth.dto.SysConfigAdminDTO;
import com.guide.auth.entity.SysConfig;
import com.guide.auth.mapper.SysConfigMapper;
import com.guide.auth.service.SysConfigAdminService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时参数管理单测：白名单、类型与范围校验、缺行插入/有行更新、保存后缓存失效。
 *
 * <p>重点在**校验**：参数是直接改系统行为的开关，写错一个值（比如把阈值写成 5）比拒绝保存糟得多，
 * 所以每个类型都要有非法值用例。
 */
class SysConfigAdminServiceTest {

    private SysConfigMapper configMapper;
    private SysConfigService sysConfigService;
    private SysConfigAdminService service;

    @BeforeEach
    void setUp() {
        configMapper = mock(SysConfigMapper.class);
        sysConfigService = mock(SysConfigService.class);
        // 模拟「库里没值」：get 回落到调用方给的默认值
        when(sysConfigService.get(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        // getInt 同样要桩：跨项校验读的是「另一个阈值的当前生效值」，
        // 不桩的话 mock 返回 0，校验会静默放行（等于没校验）
        when(sysConfigService.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> Integer.parseInt(invocation.getArgument(1).toString()));
        service = new SysConfigAdminService(configMapper, sysConfigService);
    }

    private SysConfigAdminDTO.ParamUpdate update(String key, String value) {
        SysConfigAdminDTO.ParamUpdate dto = new SysConfigAdminDTO.ParamUpdate();
        dto.setItems(List.of(item(key, value)));
        return dto;
    }

    private SysConfigAdminDTO.ParamItem item(String key, String value) {
        SysConfigAdminDTO.ParamItem item = new SysConfigAdminDTO.ParamItem();
        item.setKey(key);
        item.setValue(value);
        return item;
    }

    @Test
    @DisplayName("列表回落到代码侧默认值，并带上类型与范围供前端校验")
    void listParamsFallsBackToDefaults() {
        List<SysConfigAdminDTO.ParamVO> params = service.listParams(SysConfigAdminService.ParamGroup.LINK);

        SysConfigAdminDTO.ParamVO topK = params.stream()
                .filter(p -> SysConfigService.KEY_RETRIEVE_TOP_K.equals(p.getKey())).findFirst().orElseThrow();
        assertEquals("10", topK.getValue());
        assertEquals("10", topK.getDefaultValue());
        assertEquals("int", topK.getType());
        assertEquals("1–50", topK.getRange());

        SysConfigAdminDTO.ParamVO termReview = params.stream()
                .filter(p -> SysConfigService.KEY_TERM_MANUAL_REVIEW.equals(p.getKey())).findFirst().orElseThrow();
        assertEquals("bool", termReview.getType());
    }

    @Test
    @DisplayName("敏感词处置参数单独成组：不在链路参数里，且默认值就是约定的 60/10/30/25/60")
    void sensitiveParamsAreOwnGroup() {
        List<SysConfigAdminDTO.ParamVO> link = service.listParams(SysConfigAdminService.ParamGroup.LINK);
        assertTrue(link.stream().noneMatch(p -> p.getKey().startsWith("sensitive.")),
                "链路参数面板不该出现敏感词处置参数");

        List<SysConfigAdminDTO.ParamVO> sensitive =
                service.listParams(SysConfigAdminService.ParamGroup.SENSITIVE);
        assertEquals(5, sensitive.size());
        Map<String, String> defaults = sensitive.stream()
                .collect(Collectors.toMap(SysConfigAdminDTO.ParamVO::getKey,
                        SysConfigAdminDTO.ParamVO::getDefaultValue));
        assertEquals("60", defaults.get(SysConfigService.KEY_SENSITIVE_WINDOW_MINUTES));
        assertEquals("10", defaults.get(SysConfigService.KEY_SENSITIVE_BANNED_WARN));
        assertEquals("30", defaults.get(SysConfigService.KEY_SENSITIVE_BANNED_MUTE));
        assertEquals("25", defaults.get(SysConfigService.KEY_SENSITIVE_WATCH_WARN));
        assertEquals("60", defaults.get(SysConfigService.KEY_SENSITIVE_MUTE_MINUTES));
    }

    @Test
    @DisplayName("禁言阈值必须大于警告阈值：配反了警告永远不会触发，整批拒绝且不落库")
    void muteThresholdMustExceedWarnThreshold() {
        SysConfigAdminDTO.ParamItem warn = item(SysConfigService.KEY_SENSITIVE_BANNED_WARN, "30");
        SysConfigAdminDTO.ParamItem mute = item(SysConfigService.KEY_SENSITIVE_BANNED_MUTE, "10");
        SysConfigAdminDTO.ParamUpdate dto = new SysConfigAdminDTO.ParamUpdate();
        dto.setItems(List.of(warn, mute));

        BizException e = assertThrows(BizException.class, () -> service.updateParams(dto.getItems()));

        assertEquals(1001, e.getCode());
        assertTrue(e.getMessage().contains("必须大于警告阈值"));
        verify(configMapper, never()).insert(any(SysConfig.class));
        verify(sysConfigService, never()).refresh();
    }

    @Test
    @DisplayName("只改其中一个阈值时，拿另一个的当前生效值比较（60 分钟禁言线对不上 30 次警告线）")
    void crossCheckUsesCurrentValueForUntouchedKey() {
        // 当前生效：警告 10（默认），本次把禁言线改成 5 → 应拒
        SysConfigAdminDTO.ParamUpdate dto = update(SysConfigService.KEY_SENSITIVE_BANNED_MUTE, "5");
        assertThrows(BizException.class, () -> service.updateParams(dto.getItems()));

        // 一次改到合法组合则通过
        SysConfigAdminDTO.ParamUpdate ok = update(SysConfigService.KEY_SENSITIVE_BANNED_MUTE, "12");
        when(configMapper.selectOne(any())).thenReturn(null);
        service.updateParams(ok.getItems());
        verify(configMapper).insert(any(SysConfig.class));
    }

    @Test
    @DisplayName("提交里不涉及这两个阈值时不做跨项校验（否则改个无关参数也会被历史配置拦住）")
    void crossCheckSkippedWhenUntouched() {
        SysConfigAdminDTO.ParamUpdate dto = update(SysConfigService.KEY_SENSITIVE_WATCH_WARN, "25");

        service.updateParams(dto.getItems());

        verify(configMapper).insert(any(SysConfig.class));
    }

    @Test
    @DisplayName("保存合法值：缺行插入 + 刷新缓存")
    void updateInsertsWhenRowMissing() {
        when(configMapper.selectOne(any())).thenReturn(null);

        service.updateParams(update(SysConfigService.KEY_RETRIEVE_TOP_K, " 8 ").getItems());

        ArgumentCaptor<SysConfig> captor = ArgumentCaptor.forClass(SysConfig.class);
        verify(configMapper).insert(captor.capture());
        assertEquals(SysConfigService.KEY_RETRIEVE_TOP_K, captor.getValue().getConfigKey());
        assertEquals("8", captor.getValue().getConfigValue());
        verify(sysConfigService).refresh();
    }

    @Test
    @DisplayName("保存已有项：走更新而不是插入")
    void updateExistingRow() {
        SysConfig existing = new SysConfig();
        existing.setId("c05");
        existing.setConfigKey(SysConfigService.KEY_RETRIEVE_TOP_K);
        existing.setConfigValue("10");
        when(configMapper.selectOne(any())).thenReturn(existing);

        service.updateParams(update(SysConfigService.KEY_RETRIEVE_TOP_K, "12").getItems());

        assertEquals("12", existing.getConfigValue());
        verify(configMapper).updateById(existing);
        verify(configMapper, never()).insert(any(SysConfig.class));
    }

    @Test
    @DisplayName("不在白名单的键直接拒绝，且不写库")
    void rejectsUnknownKey() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateParams(update("llm.deepseek.api-key", "sk-123").getItems()));

        assertEquals(2008, e.getCode());
        verify(configMapper, never()).insert(any(SysConfig.class));
        verify(configMapper, never()).updateById(any(SysConfig.class));
        verify(sysConfigService, never()).refresh();
    }

    @Test
    @DisplayName("整数越界/非整数都被拒（向量召回 Top-K 只接受 1–50）")
    void rejectsBadInt() {
        BizException tooBig = assertThrows(BizException.class,
                () -> service.updateParams(update(SysConfigService.KEY_RETRIEVE_TOP_K, "999").getItems()));
        assertEquals(1001, tooBig.getCode());

        BizException notNumber = assertThrows(BizException.class,
                () -> service.updateParams(update(SysConfigService.KEY_RETRIEVE_TOP_K, "十条").getItems()));
        assertEquals(1001, notNumber.getCode());
    }

    @Test
    @DisplayName("阈值类只接受 0–1 的小数")
    void rejectsBadDecimal() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateParams(update(SysConfigService.KEY_LOW_CONFIDENCE, "5").getItems()));
        assertEquals(1001, e.getCode());

        service.updateParams(update(SysConfigService.KEY_LOW_CONFIDENCE, "0.6").getItems());
        ArgumentCaptor<SysConfig> captor = ArgumentCaptor.forClass(SysConfig.class);
        verify(configMapper).insert(captor.capture());
        assertEquals("0.6", captor.getValue().getConfigValue());
    }

    @Test
    @DisplayName("开关只接受 true/false，且归一为小写存储")
    void booleanIsNormalized() {
        when(configMapper.selectOne(any())).thenReturn(null);

        service.updateParams(update(SysConfigService.KEY_TERM_MANUAL_REVIEW, "TRUE").getItems());

        ArgumentCaptor<SysConfig> captor = ArgumentCaptor.forClass(SysConfig.class);
        verify(configMapper).insert(captor.capture());
        assertEquals("true", captor.getValue().getConfigValue());

        BizException e = assertThrows(BizException.class,
                () -> service.updateParams(update(SysConfigService.KEY_TERM_MANUAL_REVIEW, "开").getItems()));
        assertEquals(1001, e.getCode());
    }

    @Test
    @DisplayName("空值被拒：参数不允许删除式清空")
    void rejectsBlankValue() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateParams(update(SysConfigService.KEY_RETRIEVE_TOP_K, "  ").getItems()));
        assertEquals(1001, e.getCode());
    }

    @Test
    @DisplayName("批量保存先全量校验：有一项非法则一项都不落库")
    void invalidItemBlocksWholeBatch() {
        when(configMapper.selectOne(any())).thenReturn(null);
        SysConfigAdminDTO.ParamItem ok = new SysConfigAdminDTO.ParamItem();
        ok.setKey(SysConfigService.KEY_RETRIEVE_TOP_N);
        ok.setValue("5");
        SysConfigAdminDTO.ParamItem bad = new SysConfigAdminDTO.ParamItem();
        bad.setKey(SysConfigService.KEY_RETRIEVE_TOP_K);
        bad.setValue("0");
        SysConfigAdminDTO.ParamUpdate dto = new SysConfigAdminDTO.ParamUpdate();
        dto.setItems(List.of(ok, bad));

        assertThrows(BizException.class, () -> service.updateParams(dto.getItems()));

        // 校验阶段就抛了，写入阶段根本没进——合法的那一项也不落库
        verify(configMapper, never()).selectOne(any());
        verify(configMapper, never()).insert(any(SysConfig.class));
        verify(configMapper, never()).updateById(any(SysConfig.class));
        verify(sysConfigService, never()).refresh();
    }

    @Test
    @DisplayName("空清单直接返回，不做任何库操作也不清缓存")
    void emptyUpdateIsNoop() {
        service.updateParams(List.of());
        verify(configMapper, never()).selectOne(any());
        verify(sysConfigService, never()).refresh();
        verify(sysConfigService, never()).get(eq(SysConfigService.KEY_RETRIEVE_TOP_K), anyString());
    }
}
