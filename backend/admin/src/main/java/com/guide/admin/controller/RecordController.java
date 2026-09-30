package com.guide.admin.controller;

import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.chat.dto.RecordDTO;
import com.guide.chat.service.RecordService;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者端「就诊记录」接口。
 *
 * <p>除**归档**外全是只读：列表与回放是对既有事实的回顾，归档是患者对自己列表的整理
 * （收进收纳区 / 取回，不是删除）。路径不在 {@code /api/admin/**} 下，所以按 SecurityConfig
 * 只需登录、不要 ROLE_ADMIN——患者看自己的记录。越权防护在 {@link RecordService}：
 * 列表按登录用户过滤，详情与归档比对会话归属，别人的会话一律按「不存在」返回。
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

    /**
     * 归档 / 取回一条会话（患者整理自己的列表：收进收纳区或取回，**不是删除**）。
     * 归档后仍可回放，仍在这份列表数据里——只是不在主区。
     */
    @PostMapping("/sessions/{id}/archive")
    public Result<Void> archive(@PathVariable("id") String id,
                               @Valid @RequestBody RecordDTO.ArchiveReq request) {
        recordService.setArchived(currentUserId(), id, request.archived());
        return Result.ok();
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
