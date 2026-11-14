package com.guide.admin.controller;

import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import com.guide.memory.dto.MemoryDTO;
import com.guide.memory.service.MemoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者端「长期记忆」控制面（{@code /api/memory}）。
 *
 * <p>本工单只开只读列表；编辑 / 删除 / 开关 / 候选接受拒绝由后续工单补。
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

    /** 读自己的记忆：开关 + 偏好 + 背景 + 候选（本工单为空）+ 上限 */
    @GetMapping
    public Result<MemoryDTO.MemoryVO> get() {
        return Result.ok(memoryQueryService.getMemory(currentUserId()));
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
