package com.guide.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.chat.enums.SessionStatus;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话（链路 A）：一条主诉一个会话；**挂号成功（register_success）即置 closed，这是唯一的结束信号**；
 * 患者此后在同聊天页的新输入 = 开新会话（判定信号是会话状态，非模型语义判断）；
 * 一个聊天页可先后承载多个会话。
 *
 * <p>出结论（has_result=1）**不结束**会话：患者可在同一会话里继续补充症状（2026-10-14 定案，见工单 05）。
 */
@Getter
@Setter
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    private String userId;

    private SessionStatus status;

    /** 距最近一张结论卡：已追问几轮（出卡时归零，语义见 GuideService.saveConclusion） */
    private Integer askRound;

    /** 0/1 已出过导诊结论的事实标记——**不再是会话边界判据**（边界只看 status=closed） */
    private Integer hasResult;

    /**
     * 0/1 患者把这条会话收进「已归档」——**只是收纳，不是删除**：
     * 它仍可只读回放、仍在列表数据里（前端自己分组到收纳区），随时可取回。
     *
     * <p>与 {@code deleted}（逻辑删除：这条数据不存在了）刻意分开——归档是患者对**自己的列表**
     * 做的整理，删库级语义会顺带影响统计与排查。
     */
    private Integer archived;

    /**
     * 是否可续聊：**只看 status==ONGOING**（挂号置 CLOSED 是唯一的结束信号）。
     *
     * <p>出结论（has_result=1）不再是边界——患者可以紧接着补一句「那我该注意什么」，
     * 这仍是同一次就诊的续聊，不该被当成新的一次就诊（要求重新声明部位、另开会话）。
     */
    public boolean continuable() {
        return status == SessionStatus.ONGOING;
    }
}
