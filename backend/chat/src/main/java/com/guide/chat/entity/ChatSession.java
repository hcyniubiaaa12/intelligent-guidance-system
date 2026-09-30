package com.guide.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.chat.enums.SessionStatus;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话（链路 A）：一条主诉一个会话；挂号成功（register_success）即置 closed；
 * 患者此后在同聊天页的新输入 = 开新会话（判定信号是会话状态，非模型语义判断）；
 * 一个聊天页可先后承载多个会话。
 */
@Getter
@Setter
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    private String userId;

    private SessionStatus status;

    /** 追问轮次 0–3 */
    private Integer askRound;

    /** 0/1 已出导诊结论（新主诉判定双信号之一：has_result=1 或 status=closed） */
    private Integer hasResult;

    /**
     * 0/1 患者把这条会话收进「已归档」——**只是收纳，不是删除**：
     * 它仍可只读回放、仍在列表数据里（前端自己分组到收纳区），随时可取回。
     *
     * <p>与 {@code deleted}（逻辑删除：这条数据不存在了）刻意分开——归档是患者对**自己的列表**
     * 做的整理，删库级语义会顺带影响统计与排查。
     */
    private Integer archived;

    /** 是否可续聊（未出结论且未关闭）；否则下一条输入开新会话 */
    public boolean continuable() {
        return status == SessionStatus.ONGOING && (hasResult == null || hasResult == 0);
    }
}
