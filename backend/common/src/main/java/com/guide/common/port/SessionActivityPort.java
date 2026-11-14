package com.guide.common.port;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话活动端口：memory 读取对话、申领抽取配额、扫描空闲会话，都经这里回 chat 取。
 *
 * <p><b>接口在 common、实现在 chat</b>，方向是「memory 依赖 common」而不是「memory 依赖 chat」：
 * memory 模块的依赖只有 common / auth / llm（见 spec §7）。chat 的会话与消息表属于 chat 的领域，
 * memory 不直连它的 mapper。
 */
public interface SessionActivityPort {

    /** 最近对话（按时间升序，最多 limit 条；逻辑删除的消息不返回） */
    List<DialogueTurn> recentDialogue(String sessionId, int limit);

    /**
     * 原子申领一次抽取配额：{@code memory_extract_count} 未达上限时 +1 并返回 true。
     *
     * <p><b>必须是一条 UPDATE 的条件更新，不能先查再更新</b>——先查后更会在并发下超出上限。
     * 只在「真的要调模型」前调用，规则预筛没命中的轮次不计数。
     */
    boolean tryIncrementExtractCount(String sessionId, int max);

    /**
     * 找出「最后一条消息时间早于 now-idleFor」且抽取次数未达上限的会话（按最后消息时间升序）。
     *
     * @param idleFor         空闲时长（如 30 分钟）
     * @param maxExtractCount 每会话抽取次数上限
     * @param limit           单次扫描最多返回多少条
     */
    List<IdleSession> findIdleSessions(Duration idleFor, int maxExtractCount, int limit);

    /** 一条对话消息（role 为 chat 的入库编码：user / ai / question / info） */
    record DialogueTurn(String role, String content, LocalDateTime createdAt) {
    }

    /** 一条空闲会话（推送抽取所需的两个归属键） */
    record IdleSession(String sessionId, String userId) {
    }
}
