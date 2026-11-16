package com.guide.memory.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.guide.auth.entity.User;
import com.guide.auth.mapper.UserMapper;
import com.guide.auth.service.MemorySettingService;
import com.guide.common.exception.BizException;
import com.guide.memory.MpTableInfoTestSupport;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆管理（工单 06）：编辑、删除单条、一键清空、自动记忆开关。
 *
 * <p>只测服务对外的行为：给定库里的行（mock 返回），断言「写了什么、没写什么、日志留了什么」。
 * 三条口径在这里钉死：
 * <ul>
 *   <li><b>编辑只改 mvalue</b>：出处、观察时间、类型、有效期都不动——患者改的是值，不是「这句话从哪来」。</li>
 *   <li><b>归属校验失败按「不存在」返回</b>：不泄露别人的记忆是否存在。</li>
 *   <li><b>删除是逻辑删除 + 审计留痕</b>：写 user_memory_log（actor=user），不物理抹掉。</li>
 * </ul>
 */
class MemoryManageServiceTest {

    private static final String USER = "u1";
    private static final String OTHER = "u2";

    private MemoryMapper memoryMapper;
    private MemoryLogService memoryLogService;
    private MemoryVectorIndexer vectorIndexer;
    private MemoryManageService service;

    @BeforeAll
    static void initTableInfo() {
        MpTableInfoTestSupport.init(Memory.class, User.class);
    }

    @BeforeEach
    void setUp() {
        memoryMapper = mock(MemoryMapper.class);
        memoryLogService = mock(MemoryLogService.class);
        vectorIndexer = mock(MemoryVectorIndexer.class);
        service = new MemoryManageService(memoryMapper, memoryLogService, vectorIndexer);
    }

    @Test
    @DisplayName("编辑只改 mvalue：SQL 里只有值这一列，出处与观察时间保留，并写 update 日志")
    void updateOnlyChangesValue() {
        Memory row = memory("m1", USER, MemoryType.PREFERENCE, "回答方式", "讲快一点");
        row.setSourceQuote("我希望你讲快一点");
        when(memoryMapper.selectById("m1")).thenReturn(row);

        service.updateValue(USER, "m1", "  讲慢一点  ");

        ArgumentCaptor<Wrapper<Memory>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(memoryMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("mvalue").contains("updated_at");
        assertThat(sqlSet).doesNotContain("source_quote")
                .doesNotContain("observed_at")
                .doesNotContain("type")
                .doesNotContain("valid_from")
                .doesNotContain("valid_to");
        verify(memoryLogService).append("m1", USER, MemoryOp.UPDATE, "讲快一点", "讲慢一点", MemoryActor.USER);
        // 偏好不向量化：编辑不触发任何向量操作
        verifyNoInteractions(vectorIndexer);
    }

    @Test
    @DisplayName("编辑背景：retrieval_text 同步刷新并立即重算向量（否则召回仍用旧文本的向量）")
    void updateBackgroundRefreshesVectorText() {
        Memory row = memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病");
        when(memoryMapper.selectById("m1")).thenReturn(row);

        service.updateValue(USER, "m1", " 1型糖尿病 ");

        ArgumentCaptor<Wrapper<Memory>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(memoryMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("mvalue").contains("retrieval_text").contains("updated_at");
        // 快照刷新 + 重算与抽取改口共用同一处：服务侧只负责落库与委托，不自己再拼一遍
        verify(vectorIndexer).refreshBackgroundValue(eq(row), eq("1型糖尿病"), any(LocalDateTime.class));
        verify(vectorIndexer, never()).index(any(Memory.class));
    }

    @Test
    @DisplayName("编辑：别人的记忆按「不存在」拒绝，不写库也不留日志")
    void updateRejectsOtherUsersMemory() {
        when(memoryMapper.selectById("m1")).thenReturn(
                memory("m1", OTHER, MemoryType.PREFERENCE, "回答方式", "讲快一点"));

        BizException e = assertThrows(BizException.class, () -> service.updateValue(USER, "m1", "讲慢一点"));

        assertEquals(8000, e.getCode());
        verify(memoryMapper, never()).update(any(), any());
        verifyNoInteractions(memoryLogService);
    }

    @Test
    @DisplayName("编辑：不存在 / 已过期 / 已逻辑删除的记忆都按「不存在」拒绝")
    void updateRejectsMissingExpiredOrDeleted() {
        when(memoryMapper.selectById("missing")).thenReturn(null);
        Memory expired = memory("expired", USER, MemoryType.PREFERENCE, "语气", "慢一点");
        expired.setStatus(MemoryStatus.EXPIRED);
        when(memoryMapper.selectById("expired")).thenReturn(expired);
        Memory deleted = memory("deleted", USER, MemoryType.PREFERENCE, "语气", "慢一点");
        deleted.setDeleted(1);
        when(memoryMapper.selectById("deleted")).thenReturn(deleted);

        assertEquals(8000, assertThrows(BizException.class,
                () -> service.updateValue(USER, "missing", "新值")).getCode());
        assertEquals(8000, assertThrows(BizException.class,
                () -> service.updateValue(USER, "expired", "新值")).getCode());
        assertEquals(8000, assertThrows(BizException.class,
                () -> service.updateValue(USER, "deleted", "新值")).getCode());

        verify(memoryMapper, never()).update(any(), any());
        verifyNoInteractions(memoryLogService);
    }

    @Test
    @DisplayName("编辑：空值 / 超过 60 字拒绝，恰好 60 字放行")
    void updateValidatesValue() {
        when(memoryMapper.selectById("m1")).thenReturn(
                memory("m1", USER, MemoryType.PREFERENCE, "语气", "慢一点"));
        String sixty = "慢".repeat(60);
        String sixtyOne = "慢".repeat(61);

        assertEquals(1001, assertThrows(BizException.class,
                () -> service.updateValue(USER, "m1", "   ")).getCode());
        assertEquals(1001, assertThrows(BizException.class,
                () -> service.updateValue(USER, "m1", null)).getCode());
        assertEquals(1001, assertThrows(BizException.class,
                () -> service.updateValue(USER, "m1", sixtyOne)).getCode());

        service.updateValue(USER, "m1", sixty);
        verify(memoryLogService).append("m1", USER, MemoryOp.UPDATE, "慢一点", sixty, MemoryActor.USER);
    }

    @Test
    @DisplayName("删除单条：逻辑删除 + 写 delete 日志（旧值留痕，新值为空）")
    void deleteWritesLogAndLogicDeletes() {
        Memory row = memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病");
        when(memoryMapper.selectById("m1")).thenReturn(row);

        service.delete(USER, "m1");

        verify(memoryMapper).deleteById("m1");
        verify(memoryLogService).append("m1", USER, MemoryOp.DELETE, "2型糖尿病", null, MemoryActor.USER);
        verify(vectorIndexer).delete("m1");
    }

    @Test
    @DisplayName("删除：别人的记忆按「不存在」拒绝，不删也不留日志")
    void deleteRejectsOtherUsersMemory() {
        when(memoryMapper.selectById("m1")).thenReturn(
                memory("m1", OTHER, MemoryType.BACKGROUND_HEALTH, "慢病", "高血压"));

        BizException e = assertThrows(BizException.class, () -> service.delete(USER, "m1"));

        assertEquals(8000, e.getCode());
        verify(memoryMapper, never()).deleteById(any(String.class));
        verifyNoInteractions(memoryLogService);
    }

    @Test
    @DisplayName("一键清空：只删当前用户、只删 active，逐条留 delete 日志，返回删除条数")
    void clearAllOnlyClearsCurrentUserActiveMemories() {
        Memory own1 = memory("own1", USER, MemoryType.PREFERENCE, "语气", "慢一点");
        Memory own2 = memory("own2", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "高血压");
        Memory other = memory("other", OTHER, MemoryType.PREFERENCE, "语气", "快一点");
        Memory expired = memory("expired", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "已停药");
        expired.setStatus(MemoryStatus.EXPIRED);
        when(memoryMapper.selectList(any())).thenReturn(List.of(own1, other, own2, expired));

        int removed = service.clearAll(USER);

        assertThat(removed).isEqualTo(2);
        verify(memoryMapper).deleteById("own1");
        verify(memoryMapper).deleteById("own2");
        verify(memoryMapper, never()).deleteById("other");
        verify(memoryMapper, never()).deleteById("expired");
        verify(memoryLogService).append("own1", USER, MemoryOp.DELETE, "慢一点", null, MemoryActor.USER);
        verify(memoryLogService).append("own2", USER, MemoryOp.DELETE, "高血压", null, MemoryActor.USER);
        verify(memoryLogService, times(2)).append(any(), any(), eq(MemoryOp.DELETE), any(), isNull(), any());
        verify(vectorIndexer).delete("own1");
        verify(vectorIndexer).delete("own2");
        verify(vectorIndexer, never()).delete("other");
        verify(vectorIndexer, never()).delete("expired");
    }

    @Test
    @DisplayName("一键清空：没有记忆时什么都不删、返回 0")
    void clearAllWhenEmpty() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        int removed = service.clearAll(USER);

        assertThat(removed).isZero();
        verify(memoryMapper, never()).deleteById(any(String.class));
        verifyNoInteractions(memoryLogService);
    }

    @Test
    @DisplayName("自动记忆开关持久化：关闭写 0、开启写 1（走 auth 的 MemorySettingService）")
    void settingPersists() {
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setId(USER);
        when(userMapper.selectById(USER)).thenReturn(user);
        MemorySettingService settingService = new MemorySettingService(userMapper);

        settingService.setEnabled(USER, false);
        settingService.setEnabled(USER, true);

        ArgumentCaptor<LambdaUpdateWrapper<User>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userMapper, times(2)).update(isNull(), captor.capture());
        assertThat(captor.getAllValues().get(0).getParamNameValuePairs().values()).contains(0);
        assertThat(captor.getAllValues().get(1).getParamNameValuePairs().values()).contains(1);
    }

    // ---------- 夹具 ----------

    private Memory memory(String id, String userId, MemoryType type, String key, String value) {
        Memory m = new Memory();
        m.setId(id);
        m.setUserId(userId);
        m.setType(type);
        m.setMkey(key);
        m.setMvalue(value);
        m.setSourceQuote("原话出处");
        m.setObservedAt(LocalDateTime.of(2026, 11, 1, 10, 0));
        m.setStatus(MemoryStatus.ACTIVE);
        m.setDeleted(0);
        return m;
    }
}
