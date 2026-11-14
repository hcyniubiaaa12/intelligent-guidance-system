package com.guide.chat.port;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.common.port.SessionActivityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SessionActivityPort} 的 chat 侧实现：memory 要对话、要配额、要空闲列表，都从这里取。
 *
 * <p>落在这里而不是 memory：会话/消息表是 chat 的领域，端口只是「对外的窄接口」。
 * 读消息走 MyBatis-Plus（自动带 {@code deleted=0}）；配额递增与空闲扫描是自定义 SQL
 * （原因见 {@link ChatSessionMapper}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionActivityAdapter implements SessionActivityPort {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    @Override
    public List<DialogueTurn> recentDialogue(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        // 先取最近 limit 条（倒序），再翻回时间升序交给模型——模型读对话应按发生顺序
        List<ChatMessage> rows = messageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getCreatedAt)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + limit));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DialogueTurn> turns = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            ChatMessage row = rows.get(i);
            turns.add(new DialogueTurn(
                    row.getRole() == null ? null : row.getRole().getCode(),
                    row.getContent(),
                    row.getCreatedAt()));
        }
        return turns;
    }

    @Override
    public boolean tryIncrementExtractCount(String sessionId, int max) {
        if (sessionId == null || sessionId.isBlank() || max <= 0) {
            return false;
        }
        return sessionMapper.tryIncrementMemoryExtractCount(sessionId, max) > 0;
    }

    @Override
    public List<IdleSession> findIdleSessions(Duration idleFor, int maxExtractCount, int limit) {
        if (idleFor == null || maxExtractCount <= 0 || limit <= 0) {
            return List.of();
        }
        LocalDateTime idleBefore = LocalDateTime.now().minus(idleFor);
        List<ChatSession> rows = sessionMapper.findIdleSessions(idleBefore, maxExtractCount, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new IdleSession(row.getId(), row.getUserId()))
                .toList();
    }
}
