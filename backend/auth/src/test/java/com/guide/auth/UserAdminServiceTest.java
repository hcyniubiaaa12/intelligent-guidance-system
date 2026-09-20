package com.guide.auth;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.auth.dto.UserAdminDTO;
import com.guide.auth.entity.User;
import com.guide.auth.enums.UserRole;
import com.guide.auth.enums.UserStatus;
import com.guide.auth.mapper.UserMapper;
import com.guide.auth.security.JwtAuthenticationToken;
import com.guide.auth.security.LoginUser;
import com.guide.auth.service.UserAdminService;
import com.guide.auth.service.UserViolationService;
import com.guide.common.exception.BizException;
import com.guide.common.util.PageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理单测：封禁/解封、不能封禁自己、用户不存在。
 */
class UserAdminServiceTest {

    private UserMapper userMapper;
    private UserViolationService userViolationService;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userViolationService = mock(UserViolationService.class);
        service = new UserAdminService(userMapper, userViolationService);
        // 模拟当前登录管理员（userId=u001）
        LoginUser me = new LoginUser("u001", "admin", "admin", "tok001");
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(me, me.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User user(String id, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(UserRole.PATIENT);
        user.setStatus(status);
        return user;
    }

    @Test
    void banSetsStatusBanned() {
        when(userMapper.selectById("u002")).thenReturn(user("u002", UserStatus.NORMAL));
        service.ban("u002");
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    void banSelfThrowsCannotBanSelf() {
        BizException e = assertThrows(BizException.class, () -> service.ban("u001"));
        assertEquals(2006, e.getCode());
    }

    @Test
    void unbanSetsStatusNormal() {
        when(userMapper.selectById("u003")).thenReturn(user("u003", UserStatus.BANNED));
        service.unban("u003");
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    void banMissingUserThrowsNotFound() {
        when(userMapper.selectById("ghost")).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.ban("ghost"));
        assertEquals(2004, e.getCode());
    }

    @Test
    void unbanSkipsSelfCheck() {
        // 解封不受"不能封禁自己"约束（解封自己无意义但无害，规则只拦封禁方向）
        when(userMapper.selectById("u001")).thenReturn(user("u001", UserStatus.NORMAL));
        service.unban("u001");
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    @DisplayName("解除禁言交给违规处置服务（禁言状态在 user.mute_until，不在这里直接改）")
    void unmuteDelegatesToViolationService() {
        when(userViolationService.unmute("u006")).thenReturn(true);

        assertTrue(service.unmute("u006"));

        verify(userViolationService).unmute("u006");
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("列表只下发未到期的禁言：已过期的当没禁言（前端不参与时间判断）")
    void pageHidesExpiredMute() {
        User muted = user("u004", UserStatus.NORMAL);
        muted.setMuteUntil(LocalDateTime.now().plusMinutes(30));
        User expired = user("u005", UserStatus.NORMAL);
        expired.setMuteUntil(LocalDateTime.now().minusMinutes(1));
        Page<User> page = new Page<>(1, 10);
        page.setRecords(List.of(muted, expired));
        page.setTotal(2);
        when(userMapper.selectPage(any(), any())).thenReturn(page);

        PageUtil<UserAdminDTO.UserVO> result = service.pageUsers(1, 10, null);

        assertNotNull(result.getRecords().get(0).getMuteUntil());
        assertNull(result.getRecords().get(1).getMuteUntil());
    }
}
