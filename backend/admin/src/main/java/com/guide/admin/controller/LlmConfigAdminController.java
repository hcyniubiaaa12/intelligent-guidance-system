package com.guide.admin.controller;

import com.guide.auth.dto.SysConfigAdminDTO;
import com.guide.auth.service.SysConfigAdminService;
import com.guide.common.api.Result;
import com.guide.llm.dto.LlmDiagDTO;
import com.guide.llm.service.LlmDiagnosticService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 LLM 配置页（仅 ROLE_ADMIN）：
 * 模型接入信息（只读，密钥来自 application-local.yml 不进库）、运行时参数读写、三路连通性探测。
 */
@RestController
@RequestMapping("/api/admin/llm")
@RequiredArgsConstructor
public class LlmConfigAdminController {

    private final LlmDiagnosticService diagnosticService;
    private final SysConfigAdminService sysConfigAdminService;

    /** 模型接入信息：模型名与地址可读，密钥只回是否已配置 */
    @GetMapping("/models")
    public Result<LlmDiagDTO.ModelInfo> models() {
        return Result.ok(diagnosticService.modelInfo());
    }

    /** 受管运行时参数（检索 Top-K/Top-N、追问上限、阈值、术语审核开关） */
    @GetMapping("/params")
    public Result<List<SysConfigAdminDTO.ParamVO>> params() {
        return Result.ok(sysConfigAdminService.listParams());
    }

    /** 批量保存参数：按白名单类型校验，任一项不合法整批不生效 */
    @PutMapping("/params")
    public Result<Void> saveParams(@Valid @RequestBody SysConfigAdminDTO.ParamUpdate update) {
        sysConfigAdminService.updateParams(update.getItems());
        return Result.ok();
    }

    /** 连通性测试：三路模型各发一次最小请求（人工触发，同步返回） */
    @PostMapping("/probe")
    public Result<List<LlmDiagDTO.ProbeResult>> probe() {
        return Result.ok(diagnosticService.probeAll());
    }
}
