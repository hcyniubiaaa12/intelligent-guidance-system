package com.guide.common.event;

/**
 * 一轮导诊完成事件（旁路信号，不参与本轮 SSE 与落库）。
 *
 * <p>发布方是 chat（{@code ChatService.handle} 的 finally），消费方是 memory 的抽取监听器。
 * 事件放 common 的理由：memory **不依赖 chat**，两侧只能通过公共契约相遇；
 * 事件里不带消息正文——消费方要对话时经 {@link com.guide.common.port.SessionActivityPort}
 * 回 chat 读，避免事件体成为第二份对话副本。
 *
 * @param userId    会话归属用户（抽取结果的落库归属）
 * @param sessionId 会话 id（抽取次数配额按会话计）
 */
public record TurnCompletedEvent(String userId, String sessionId) {
}
