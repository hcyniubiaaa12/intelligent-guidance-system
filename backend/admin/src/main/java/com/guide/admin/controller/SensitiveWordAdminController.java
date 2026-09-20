package com.guide.admin.controller;

import com.guide.auth.dto.SysConfigAdminDTO;
import com.guide.auth.dto.UserAdminDTO;
import com.guide.auth.service.SensitiveWordAdminService;
import com.guide.auth.service.SysConfigAdminService;
import com.guide.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端敏感词库（链路 A 入口校验词库维护，仅 ROLE_ADMIN）：
 * 分页查询、单条添加、批量导入、启停用、观察词转禁止词、删除；以及处置规则（窗口与阈值，sys_config）。
 */
@RestController
@RequestMapping("/api/admin/sensitive-words")
@RequiredArgsConstructor
public class SensitiveWordAdminController {

    private final SensitiveWordAdminService wordService;
    private final SysConfigAdminService sysConfigAdminService;

    @GetMapping
    public Result<?> page(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "10") long size,
                          @RequestParam(required = false) String type) {
        return Result.ok(wordService.pageWords(current, size, type));
    }

    /** 处置规则：窗口、两条禁止词阈值、观察词阈值、禁言时长（与词库同页，因为二者共同决定"触发之后会怎样"） */
    @GetMapping("/rules")
    public Result<List<SysConfigAdminDTO.ParamVO>> rules() {
        return Result.ok(sysConfigAdminService.listParams(SysConfigAdminService.ParamGroup.SENSITIVE));
    }

    /** 批量保存处置规则：任一项非法（含禁言阈值 ≤ 警告阈值）则整批不生效 */
    @PutMapping("/rules")
    public Result<Void> saveRules(@Valid @RequestBody SysConfigAdminDTO.ParamUpdate update) {
        sysConfigAdminService.updateParams(update.getItems());
        return Result.ok();
    }

    @PostMapping
    public Result<Void> add(@Valid @RequestBody UserAdminDTO.WordAdd dto) {
        wordService.add(dto);
        return Result.ok();
    }

    @PostMapping("/import")
    public Result<UserAdminDTO.ImportResult> importWords(@Valid @RequestBody UserAdminDTO.WordImport dto) {
        return Result.ok(wordService.importWords(dto));
    }

    @PostMapping("/{id}/toggle")
    public Result<Void> toggleEnabled(@PathVariable String id) {
        wordService.toggleEnabled(id);
        return Result.ok();
    }

    @PostMapping("/{id}/to-banned")
    public Result<Void> convertToBanned(@PathVariable String id) {
        wordService.convertToBanned(id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        wordService.delete(id);
        return Result.ok();
    }
}
