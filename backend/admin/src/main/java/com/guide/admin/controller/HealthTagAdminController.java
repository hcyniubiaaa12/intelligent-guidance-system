package com.guide.admin.controller;

import com.guide.auth.dto.HealthTagAdminDTO;
import com.guide.auth.service.HealthTagAdminService;
import com.guide.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端「慢病标签词表」维护（单据 05，仅 ROLE_ADMIN）。
 *
 * <p><b>权限</b>：路径在 {@code /api/admin/**} 下，由 {@code SecurityConfig} 统一要求
 * {@code hasRole("ADMIN")}（无 {@code @PreAuthorize}）；非管理员访问由
 * {@code accessDeniedHandler} 返回 <b>403</b>，未登录返回 401——与既有 admin controller 同一表达方式。
 *
 * <p><b>口径</b>：启用 / 停用只作用在患者端选项入口；已保存档案里的标签照常参与召回
 * （见 {@link HealthTagAdminService} 类注释）。本单据只做后端接口，**管理端页面属范围外**。
 */
@RestController
@RequestMapping("/api/admin/health-tags")
@RequiredArgsConstructor
public class HealthTagAdminController {

    private final HealthTagAdminService healthTagAdminService;

    /** 列出全部慢病标签（含启用状态；停用项也在列表里，可重新启用） */
    @GetMapping
    public Result<List<HealthTagAdminDTO.TagVO>> list() {
        return Result.ok(healthTagAdminService.listAll());
    }

    /** 新增标签（词 + 类别，默认启用） */
    @PostMapping
    public Result<HealthTagAdminDTO.TagVO> add(@Valid @RequestBody HealthTagAdminDTO.TagAdd request) {
        return Result.ok(healthTagAdminService.add(request));
    }

    /** 启用 / 停用某个标签（只作用患者端选项；已保存档案照常参与召回） */
    @PostMapping("/{id}/toggle")
    public Result<HealthTagAdminDTO.TagVO> toggle(@PathVariable("id") String id) {
        return Result.ok(healthTagAdminService.toggle(id));
    }
}
