package com.guide.chat.port;

import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.common.port.SessionActivityPort.DialogueTurn;
import com.guide.common.port.SessionActivityPort.IdleSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话活动端口（chat 侧实现）：memory 经它取对话、申领配额、列空闲会话。
 *
 * <p>只测适配器对外的三条行为，不测 SQL 文本——SQL 的正确性由集成环境验证；
 * 这里钉的是「顺序、编码、参数传递」这些容易在重构中悄悄变掉的契约。
 */
class ChatSessionActivityAdapterTest {

    private static final String SESSION = "s1";

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private ChatSessionActivityAdapter adapter;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        adapter = new ChatSessionActivityAdapter(sessionMapper, messageMapper);
    }

    @Test
    @DisplayName("最近对话按时间升序返回：先取最近 N 条（倒序），再翻回发生顺序给模型")
    void recentDialogueIsChronological() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message(MessageRole.AI, "先观察", 2),
                message(MessageRole.USER, "胸口闷", 1)));

        List<DialogueTurn> turns = adapter.recentDialogue(SESSION, 8);

        assertThat(turns).extracting(DialogueTurn::content).containsExactly("胸口闷", "先观察");
        assertThat(turns).extracting(DialogueTurn::role).containsExactly("user", "ai");
        verify(messageMapper).selectList(any());
    }

    @Test
    @DisplayName("空会话 / 非法参数不查库：直接返回空列表")
    void blankSessionReturnsEmpty() {
        assertThat(adapter.recentDialogue(null, 8)).isEmpty();
        assertThat(adapter.recentDialogue("  ", 8)).isEmpty();
        assertThat(adapter.recentDialogue(SESSION, 0)).isEmpty();
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("配额申领：影响行数 1 = true，0 = false（达到上限由 SQL 的 WHERE 判定）")
    void incrementMapsAffectedRows() {
        when(sessionMapper.tryIncrementMemoryExtractCount(SESSION, 3)).thenReturn(1, 0);

        assertThat(adapter.tryIncrementExtractCount(SESSION, 3)).isTrue();
        assertThat(adapter.tryIncrementExtractCount(SESSION, 3)).isFalse();
        verify(sessionMapper, never()).updateById(any(ChatSession.class));
    }

    @Test
    @DisplayName("空闲扫描：截断时间约为 now-30min，并把行映射成 (sessionId,userId)")
    void idleScanPassesCutoffAndMapsRows() {
        LocalDateTime before = LocalDateTime.now();
        ChatSession row = new ChatSession();
        row.setId("s1");
        row.setUserId("u1");
        when(sessionMapper.findIdleSessions(any(), eq(3), eq(50))).thenReturn(List.of(row));

        List<IdleSession> sessions = adapter.findIdleSessions(Duration.ofMinutes(30), 3, 50);

        assertThat(sessions).containsExactly(new IdleSession("s1", "u1"));
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).findIdleSessions(cutoff.capture(), eq(3), eq(50));
        assertThat(cutoff.getValue())
                .isBetween(before.minusMinutes(30).minusSeconds(5), LocalDateTime.now().minusMinutes(30).plusSeconds(5));
        // 纯读：扫描不该顺手写任何会话
        verify(sessionMapper, never()).tryIncrementMemoryExtractCount(anyString(), anyInt());
    }

    private ChatMessage message(MessageRole role, String content, int minute) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(SESSION);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 11, 14, 10, minute));
        return message;
    }
}
