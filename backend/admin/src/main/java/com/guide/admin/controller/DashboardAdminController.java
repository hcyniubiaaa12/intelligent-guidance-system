package com.guide.admin.controller;

import com.guide.common.api.Result;
import com.guide.common.util.PageUtil;
import com.guide.stats.dto.DashboardDTO;
import com.guide.stats.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端数据看板（仅 ROLE_ADMIN）：
 * 首屏聚合（KPI / 每日趋势 / 根因分布）+ 最近导诊记录分页。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardAdminController {

    private final DashboardService dashboardService;

    /** days 缺省 7（页面口径：近 7 天），上限 90 天 */
    @GetMapping("/overview")
    public Result<DashboardDTO.OverviewVO> overview(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(dashboardService.overview(days));
    }

    @GetMapping("/records")
    public Result<PageUtil> records(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "10") long size) {
        return Result.ok(dashboardService.records(current, size));
    }
}
