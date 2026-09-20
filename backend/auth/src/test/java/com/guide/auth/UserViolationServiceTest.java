package com.guide.auth;

import com.guide.auth.entity.User;
import com.guide.auth.entity.UserViolation;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.enums.UserRole;
import com.guide.auth.enums.UserStatus;
import com.guide.auth.enums.ViolationLevel;
import com.guide.auth.mapper.UserMapper;
import com.guide.auth.mapper.UserViolationMapper;
import com.guide.auth.service.UserViolationService;
import com.guide.common.exception.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 违规处置落库单测（auth）：禁言状态读写、警告/禁言留痕、窗口内是否已告警。
 *
 * <p>重点在**到期语义**：禁言没有定时任务，全靠「当前时间 vs mute_until」判断，
 * 这里就是那条判断的唯一守卫——判断写反了，禁言要么永不生效、要么永久生效。
 */
class UserViolationServiceTest {

    private UserMapper userMapper;
    private UserViolationMapper violationMapper;
    private UserViolationService service;

    @BeforeAll
    static void initTableInfo() {
        // 禁言/解禁用 lambda 更新语句，而 Update.set 会立即解析列名 → 需要先注册表信息
        MpTableInfoTestSupport.init(User.class);
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        violationMapper = mock(UserViolationMapper.class);
        service = new UserViolationService(userMapper, violationMapper);
    }

    private User user(String id, LocalDateTime muteUntil) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(UserRole.PATIENT);
        user.setStatus(UserStatus.NORMAL);
        user.setMuteUntil(muteUntil);
        return user;
    }

    @Test
    @DisplayName("禁言状态：未设置、已过期都算未禁言；未到期才返回截止时间")
    void muteState() {
        when(userMapper.selectById("a")).thenReturn(user("a", null));
        when(userMapper.selectById("b")).thenReturn(user("b", LocalDateTime.now().minusMinutes(1)));
        when(userMapper.selectById("c")).thenReturn(user("c", LocalDateTime.now().plusMinutes(30)));

        assertTrue(service.muteUntil("a").isEmpty());
        assertTrue(service.muteUntil("b").isEmpty(), "已过期的禁言必须当未禁言，否则用户永远出不来");
        assertTrue(service.muteUntil("c").isPresent());
        assertTrue(service.muteUntil(null).isEmpty());
    }

    @Test
    @DisplayName("剩余分钟数向上取整，不足一分钟也返回 1（话术里不能出现「0 分钟」）")
    void remainingMinutesNeverZero() {
        assertEquals(1, service.remainingMinutes(LocalDateTime.now().plusSeconds(3)));
        assertTrue(service.remainingMinutes(LocalDateTime.now().plusMinutes(59)) >= 58);
    }

    @Test
    @DisplayName("警告留痕：级别 warn、带上触发类型与当时口径（命中数/窗口/阈值）")
    void warnRecordsSnapshot() {
        service.warn("u1", SensitiveWordType.BANNED, 12, 60, 10, LocalDateTime.now());

        ArgumentCaptor<UserViolation> captor = ArgumentCaptor.forClass(UserViolation.class);
        verify(violationMapper).insert(captor.capture());
        UserViolation saved = captor.getValue();
        assertEquals("u1", saved.getUserId());
        assertEquals(ViolationLevel.WARN, saved.getLevel());
        assertEquals(SensitiveWordType.BANNED, saved.getHitType());
        assertEquals(12, saved.getHitCount());
        assertEquals(60, saved.getWindowMinutes());
        assertEquals(10, saved.getThreshold());
        assertEquals(null, saved.getMuteUntil());
    }

    @Test
    @DisplayName("禁言：状态与留痕一起写（否则会出现「已被禁言但查不到为什么」）")
    void muteWritesStateAndRecord() {
        when(userMapper.selectById("u2")).thenReturn(user("u2", null));
        LocalDateTime until = LocalDateTime.now().plusMinutes(60);

        service.mute("u2", SensitiveWordType.BANNED, 30, 60, 30, until, LocalDateTime.now());

        verify(userMapper).update(any(), any());
        ArgumentCaptor<UserViolation> captor = ArgumentCaptor.forClass(UserViolation.class);
        verify(violationMapper).insert(captor.capture());
        assertEquals(ViolationLevel.MUTE, captor.getValue().getLevel());
        assertEquals(until, captor.getValue().getMuteUntil());
    }

    @Test
    @DisplayName("用户不存在时禁言报错，不留下无主的留痕")
    void muteMissingUserThrows() {
        when(userMapper.selectById("ghost")).thenReturn(null);

        assertThrows(BizException.class, () -> service.mute("ghost", SensitiveWordType.BANNED,
                30, 60, 30, LocalDateTime.now().plusMinutes(60), LocalDateTime.now()));
        verify(violationMapper, never()).insert(any(UserViolation.class));
    }

    @Test
    @DisplayName("解除禁言：在禁言中才写状态并返回 true；不在禁言中返回 false 且不写库")
    void unmute() {
        when(userMapper.selectById("m1")).thenReturn(user("m1", LocalDateTime.now().plusMinutes(10)));
        when(userMapper.selectById("m2")).thenReturn(user("m2", LocalDateTime.now().minusMinutes(10)));

        assertTrue(service.unmute("m1"));
        assertFalse(service.unmute("m2"));

        // 只应写一次（m1）；m2 不在禁言中，不该产生任何更新
        verify(userMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("空用户集合不查库（否则会生成空 IN 查询）")
    void listSinceWithEmptyIdsSkipsQuery() {
        assertEquals(List.of(), service.listSince(List.of(), LocalDateTime.now().minusMinutes(60)));
        verify(violationMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("窗口内是否已告警：由 mapper 计数决定（判据交给 SQL，不在内存里过滤）")
    void warnedWithin() {
        when(violationMapper.selectCount(any())).thenReturn(1L);
        assertTrue(service.warnedWithin("u1", SensitiveWordType.BANNED, LocalDateTime.now().minusMinutes(60)));

        when(violationMapper.selectCount(any())).thenReturn(0L);
        assertFalse(service.warnedWithin("u1", SensitiveWordType.WATCH, LocalDateTime.now().minusMinutes(60)));
    }

    @Test
    @DisplayName("禁言中查询返回截止时间（chat 用它算剩余分钟）")
    void muteUntilReturnsDeadline() {
        LocalDateTime until = LocalDateTime.now().plusMinutes(30);
        when(userMapper.selectById("u3")).thenReturn(user("u3", until));

        Optional<LocalDateTime> result = service.muteUntil("u3");

        assertEquals(until, result.orElseThrow());
    }
}
