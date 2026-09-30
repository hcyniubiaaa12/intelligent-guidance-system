package com.guide.chat.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 消息角色（chat，链路 A）；QUESTION 即追问消息、INFO 即资料回答。
 */
@Getter
@RequiredArgsConstructor
public enum MessageRole {

    USER("user"),
    /** 普通回答（含敏感词拦截话术、处置提示、模型未按协议声明时降级的回答） */
    AI("ai"),
    /** 追问：系统发出的补充提问（占追问轮次） */
    QUESTION("question"),
    /** 资料回答：患者问知识库内容而非描述症状时的如实复述（不占追问轮次，2026-09-30 加） */
    INFO("info");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;
}
