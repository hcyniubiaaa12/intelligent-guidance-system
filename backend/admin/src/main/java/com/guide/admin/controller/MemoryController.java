package com.guide.admin.controller;

import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.auth.service.MemorySettingService;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import com.guide.memory.dto.MemoryDTO;
import com.guide.memory.service.MemoryManageService;
import com.guide.memory.service.MemoryQueryService;
import com.guide.memory.service.ProfileCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者端「长期记忆」控制面（{@code /api/memory}）。
 *
 * <p>读列表、改开关、改一条值、删一条、清空全部；候选的接受 / 拒绝也在这里
 * （候选**不是记忆**，但控制面同属患者对「系统记住了什么」的处置）。
 * 路径不在 {@code /api/admin/**} 下，按 SecurityConfig 只需登录、不要 ROLE_ADMIN。
 *
 * <p>越权防护天然成立：{@code userId} 只从登录态取（{@link LoginUserHolder}），
 * 请求体与参数里都没有 userId，患者无法指向别人的记忆。未登录按既有约定返回 401。
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryQueryService memoryQueryService;
    private final MemoryManageService memoryManageService;
    private final MemorySettingService memorySettingService;
    private final ProfileCandidateService profileCandidateService;

    /** 读自己的记忆：开关 + 偏好 + 背景 + 待确认候选 + 上限 */
    @GetMapping
    public Result<MemoryDTO.MemoryVO> get() {
        return Result.ok(memoryQueryService.getMemory(currentUserId()));
    }

    /**
     * 自动记忆开关：关闭只停抽取与注入，旧记忆保留可见（页面标「已暂停」）；
     * 健康档案不受影响。
     */
    @PutMapping("/setting")
    public Result<Void> setEnabled(@RequestBody MemoryDTO.SettingReq request) {
        if (request == null || request.getMemoryEnabled() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "memoryEnabled 不能为空");
        }
        memorySettingService.setEnabled(currentUserId(), request.getMemoryEnabled());
        return Result.ok();
    }

    /** 编辑一条记忆的值：只改 mvalue，出处与观察时间由后端保留 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody MemoryDTO.ValueReq request) {
        memoryManageService.updateValue(currentUserId(), id, request == null ? null : request.getValue());
        return Result.ok();
    }

    /** 删除一条记忆（逻辑删除 + 审计留痕） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        memoryManageService.delete(currentUserId(), id);
        return Result.ok();
    }

    /** 清空当前用户的全部生效记忆（逻辑删除），返回删除条数 */
    @DeleteMapping
    public Result<Integer> clearAll() {
        return Result.ok(memoryManageService.clearAll(currentUserId()));
    }

    /**
     * 接受一条档案候选：可选先改值再收。写档案 + 标 accepted + 审计（op=promote）。
     * 空 body 也接受（按原候选值）。
     */
    @PostMapping("/candidates/{id}/accept")
    public Result<Void> acceptCandidate(@PathVariable String id,
                                        @RequestBody(required = false) MemoryDTO.CandidateAcceptReq request) {
        profileCandidateService.accept(currentUserId(), id, request == null ? null : request.getValue());
        return Result.ok();
    }

    /** 拒绝一条档案候选：只标 rejected，不写档案；同一值以后不再提议 */
    @PostMapping("/candidates/{id}/reject")
    public Result<Void> rejectCandidate(@PathVariable String id) {
        profileCandidateService.reject(currentUserId(), id);
        return Result.ok();
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
