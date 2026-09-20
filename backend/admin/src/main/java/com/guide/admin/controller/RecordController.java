package com.guide.admin.controller;

import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.chat.dto.RecordDTO;
import com.guide.chat.service.RecordService;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者端「就诊记录」接口（只读）。
 *
 * <p>路径不在 {@code /api/admin/**} 下，所以按 SecurityConfig 只需登录、不要 ROLE_ADMIN——
 * 患者看自己的记录。越权防护在 {@link RecordService}：列表按登录用户过滤，
 * 详情比对会话归属，别人的会话一律按「不存在」返回。
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    /** 我的会话列表（按天分组，最新在前）——「全部对话」与「挂号历史」共用这一份 */
    @GetMapping("/sessions")
    public Result<RecordDTO.SessionListVO> sessions() {
        return Result.ok(recordService.listSessions(currentUserId()));
    }

    /** 一条会话的完整回放：对话正文 + 用户问题书签 + 结论卡 */
    @GetMapping("/sessions/{id}")
    public Result<RecordDTO.SessionDetailVO> detail(@PathVariable("id") String id) {
        return Result.ok(recordService.sessionDetail(currentUserId(), id));
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
