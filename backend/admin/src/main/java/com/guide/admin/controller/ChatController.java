package com.guide.admin.controller;

import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.chat.dto.ChatDTO;
import com.guide.chat.service.ChatService;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import com.guide.feedback.enums.TrackStage;
import com.guide.feedback.service.TrackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 患者端导诊接口（链路 A + 链路 C 前半）。
 * 对话走 SSE（POST + Bearer 头，前端用 fetch 流式读取——EventSource 无法带请求头与请求体）；
 * 挂号确认 = register_success 触点的事实来源：写 actual_dept 后由本层补埋点（旁路）。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final TrackService trackService;

    /** 发送消息并建立 SSE 流（delta / question / result / done / error） */
    @PostMapping(value = "/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(@Valid @RequestBody ChatDTO.MessageReq request) {
        return chatService.stream(currentUserId(), request);
    }

    /** 挂号页科室列表（仅启用科室） */
    @GetMapping("/depts")
    public Result<List<ChatDTO.DeptVO>> depts() {
        return Result.ok(chatService.listDepts());
    }

    /** 挂号确认：写 actual_dept 与命中标记、会话置 closed，随后旁路补 register_success 埋点 */
    @PostMapping("/register")
    public Result<ChatDTO.RegisterVO> register(@Valid @RequestBody ChatDTO.RegisterReq request) {
        String userId = currentUserId();
        ChatDTO.RegisterVO result = chatService.confirmRegister(userId, request);
        try {
            trackService.track(TrackStage.REGISTER_SUCCESS, request.getRecordId(), userId, result.deptId());
        } catch (Exception e) {
            // 埋点是旁路信号，失败不回滚挂号事实
            log.warn("register_success 埋点落库失败（不影响挂号）：{}", e.getMessage());
        }
        return Result.ok(result);
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
