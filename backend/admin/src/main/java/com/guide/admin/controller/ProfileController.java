package com.guide.admin.controller;

import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.auth.service.HealthProfileService;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 患者端「健康档案」接口（选填）。
 *
 * <p>路径不在 {@code /api/admin/**} 下，所以按 SecurityConfig 只需登录、不要 ROLE_ADMIN——
 * 患者读写自己那一份。未登录按既有约定返回 401。
 *
 * <p>越权防护天然成立：{@code userId} 只从登录态取（{@code LoginUserHolder}），请求体里没有 userId，
 * 患者无法指向别人的档案。上限校验在 {@link HealthProfileService}（不信任前端）。
 */
@Slf4j
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final HealthProfileService healthProfileService;

    /** 读自己那份档案（不存在返回空档案，含编辑上限与下拉选项） */
    @GetMapping
    public Result<HealthProfileDTO.ProfileVO> get() {
        return Result.ok(healthProfileService.getProfile(currentUserId()));
    }

    /**
     * 整份覆盖写：一次提交全部字段，未填即清空。
     * 标签超条数 / 自由文本超字数时后端拒绝并返回可展示错误（前端硬限只是提示，不构成约束）。
     */
    @PutMapping
    public Result<Void> save(@RequestBody HealthProfileDTO.ProfileSaveReq request) {
        healthProfileService.saveProfile(currentUserId(), request);
        return Result.ok();
    }

    /** 标签词表（仅启用项）：患者端选项加载用，只读 */
    @GetMapping("/tags")
    public Result<List<HealthProfileDTO.TagVO>> tags() {
        return Result.ok(healthProfileService.listEnabledTags());
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
