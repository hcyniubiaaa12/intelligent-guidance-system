package com.guide.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.entity.User;
import com.guide.auth.mapper.UserMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 自动记忆开关（{@code user.memory_enabled}，属主在 auth）。
 *
 * <p>memory 模块只经这里读写开关，**不直接注入 {@link UserMapper}**——用户表是 auth 的领域，
 * 跨模块直连 mapper 会把「一张表的读写口径」散到两个模块里。
 *
 * <p>语义：关的是「自动记忆」——停止抽取、停止注入偏好与背景；**健康档案完全不受影响**。
 * 旧记忆仍留在库里可见（页面标「已暂停」）。
 */
@Service
@RequiredArgsConstructor
public class MemorySettingService {

    private final UserMapper userMapper;

    /** 未取到用户时按关闭处理（不凭一个不存在的账号注入记忆） */
    public boolean isEnabled(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        // 老数据 / 迁移遗漏 NULL 时按开启处理：默认值是 1，NULL 不应被读成「关闭」
        return user.getMemoryEnabled() == null || user.getMemoryEnabled() != 0;
    }

    /**
     * 设置开关。显式走 {@code LambdaUpdateWrapper.set} 而不是 {@code updateById}：
     * 后者跳过 null 字段，而「写 0」必须真的落库。
     */
    public void setEnabled(String userId, boolean enabled) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getMemoryEnabled, enabled ? 1 : 0)
                .eq(User::getId, userId));
    }
}
