package com.guide.admin.controller;

import com.guide.admin.dto.UserRiskDTO;
import com.guide.admin.service.UserRiskService;
import com.guide.auth.dto.UserAdminDTO;
import com.guide.auth.service.UserAdminService;
import com.guide.common.api.Result;
import com.guide.common.util.PageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户管理（链路 D 配套，仅 ROLE_ADMIN）：用户分页（含窗口内触发次数与处置）、
 * 封禁/解封、解除禁言、违规明细。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final UserRiskService userRiskService;

    /**
     * 用户分页。每行带上「近 N 分钟触发词次（禁止/观察）+ 禁言至 + 窗口内警告次数」，
     * 窗口长度与判定口径同源（sys_config），前端据此显示，不写死文案。
     */
    @GetMapping
    public Result<?> page(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "10") long size,
                          @RequestParam(required = false) String keyword) {
        PageUtil<UserAdminDTO.UserVO> page = userAdminService.pageUsers(current, size, keyword);
        userRiskService.fillHits(page.getRecords());
        return Result.ok(page);
    }

    /** 违规明细：窗口内按词聚合的命中 + 处置记录（点开行看"到底触发了什么"） */
    @GetMapping("/{id}/violations")
    public Result<UserRiskDTO.DetailVO> violations(@PathVariable String id) {
        return Result.ok(userRiskService.detail(id));
    }

    @PostMapping("/{id}/ban")
    public Result<Void> ban(@PathVariable String id) {
        userAdminService.ban(id);
        return Result.ok();
    }

    @PostMapping("/{id}/unban")
    public Result<Void> unban(@PathVariable String id) {
        userAdminService.unban(id);
        return Result.ok();
    }

    /** 解除禁言：禁言本会到期自动解除，这里用于需要立即放行的场景 */
    @PostMapping("/{id}/unmute")
    public Result<Boolean> unmute(@PathVariable String id) {
        return Result.ok(userAdminService.unmute(id));
    }
}
