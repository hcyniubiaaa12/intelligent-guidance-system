package com.guide.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 鉴权请求/响应 DTO（链路 D）。
 */
public final class AuthDTO {

    private AuthDTO() {
    }

    @Getter
    @Setter
    public static class Login {
        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Getter
    @Setter
    public static class Register {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3-32 位之间")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
        private String password;

        /** 昵称，非必填 */
        private String nickname;

        /** 性别（选填）：male / female；注册成功后随档案一起写入 */
        private String gender;

        /** 出生年（选填，1900–当前年）；注册成功后随档案一起写入 */
        private Integer birthYear;
    }

    /** 登录/注册成功响应：token + 用户信息 */
    @Getter
    @Setter
    public static class LoginVO {
        private String token;
        private String userId;
        private String username;
        private String nickname;
        private String role;
    }
}
