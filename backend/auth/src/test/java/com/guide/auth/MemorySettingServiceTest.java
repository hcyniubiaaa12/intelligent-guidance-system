package com.guide.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.guide.auth.entity.User;
import com.guide.auth.mapper.UserMapper;
import com.guide.auth.service.MemorySettingService;
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动记忆开关（user.memory_enabled，属主 auth）。
 *
 * <p>两个口径在这里钉死：① 读开关时 NULL 不能当成「关闭」（默认值就是 1）；
 * ② 写开关必须显式 set，写 0 要真的落到 SQL 里（updateById 会跳过 null、也不能表达 0/1 的语义差异）。
 */
class MemorySettingServiceTest {

    private UserMapper userMapper;
    private MemorySettingService service;

    @BeforeAll
    static void initTableInfo() {
        MpTableInfoTestSupport.init(User.class);
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new MemorySettingService(userMapper);
    }

    @Test
    @DisplayName("默认开启：库里是 1 → true；NULL（迁移遗漏）也按开启处理")
    void enabledByDefault() {
        when(userMapper.selectById("u1")).thenReturn(user(1));
        when(userMapper.selectById("u2")).thenReturn(user(null));

        assertTrue(service.isEnabled("u1"));
        assertTrue(service.isEnabled("u2"));
    }

    @Test
    @DisplayName("关闭后读回 false")
    void disabled() {
        when(userMapper.selectById("u1")).thenReturn(user(0));

        assertFalse(service.isEnabled("u1"));
    }

    @Test
    @DisplayName("取不到用户 / 空 userId：按关闭处理，不凭不存在的账号注入记忆")
    void missingUserIsOff() {
        when(userMapper.selectById("ghost")).thenReturn(null);

        assertFalse(service.isEnabled("ghost"));
        assertFalse(service.isEnabled(null));
        assertFalse(service.isEnabled("  "));
    }

    @Test
    @DisplayName("写开关：显式 set memory_enabled，关闭时 SQL 里写的是 0")
    void setDisabledWritesZero() {
        when(userMapper.selectById("u1")).thenReturn(user(1));

        service.setEnabled("u1", false);

        ArgumentCaptor<LambdaUpdateWrapper<User>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<User> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("memory_enabled");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(0);
    }

    @Test
    @DisplayName("写开关：用户不存在 → USER_NOT_FOUND，且不更新任何行")
    void setEnabledMissingUser() {
        when(userMapper.selectById("ghost")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.setEnabled("ghost", true));

        assertEquals(2004, e.getCode());
        verify(userMapper, never()).update(any(), any());
    }

    private User user(Integer memoryEnabled) {
        User user = new User();
        user.setId("u1");
        user.setMemoryEnabled(memoryEnabled);
        return user;
    }
}
