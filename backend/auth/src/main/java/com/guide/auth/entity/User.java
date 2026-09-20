package com.guide.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.auth.enums.UserRole;
import com.guide.auth.enums.UserStatus;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户（链路 D）：登录注册；登录时校验 status（封禁即拒绝）；管理员由初始化脚本创建。
 */
@Getter
@Setter
@TableName("user")
public class User extends BaseEntity {

    private String username;

    /** 密码（加密存储） */
    private String password;

    private UserRole role;

    private String nickname;

    private UserStatus status;

    /**
     * 禁言截止时间：{@code null} 或已过期 = 未禁言。
     *
     * <p>刻意与 {@link #status} 分开：封禁是管理员的处置（登录即拒绝、需人工解封），
     * 禁言是敏感词累计触发的处置（**只禁发言、仍可登录**，到期自动解除——不引入定时任务，
     * 每次发言前比一次当前时间即可）。
     */
    private LocalDateTime muteUntil;
}
