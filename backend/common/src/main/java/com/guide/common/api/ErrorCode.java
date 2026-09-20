package com.guide.common.api;

/**
 * 错误码枚举：code 统一按模块分段。
 * common 1xxx / auth 2xxx / chat 3xxx / rag 4xxx / kb 5xxx / feedback 6xxx / llm 7xxx。
 */
public enum ErrorCode {

    // common 1xxx
    INTERNAL_ERROR(1000, "系统内部错误"),
    PARAM_INVALID(1001, "参数校验失败"),

    // auth 2xxx
    UNAUTHORIZED(2000, "未登录或登录已过期"),
    FORBIDDEN(2001, "无权限访问"),
    USERNAME_EXISTS(2002, "用户名已存在"),
    USER_BANNED(2003, "账号已被封禁"),
    USER_NOT_FOUND(2004, "用户不存在"),
    SENSITIVE_WORD_EXISTS(2005, "敏感词已存在"),
    CANNOT_BAN_SELF(2006, "不能封禁自己的账号"),
    SENSITIVE_WORD_NOT_FOUND(2007, "敏感词不存在"),
    CONFIG_KEY_UNKNOWN(2008, "不支持的配置项"),

    // chat 3xxx
    SESSION_CLOSED(3000, "会话已结束，请重新描述症状"),
    SENSITIVE_BLOCKED(3001, "输入包含不允许的内容"),

    // rag 4xxx
    RAG_EMPTY(4000, "知识库暂无相关内容"),

    // kb 5xxx
    DOC_NOT_FOUND(5000, "文档不存在"),
    DEPT_NOT_FOUND(5001, "科室不存在"),
    DEPT_DISABLED(5002, "科室已停用，暂不可挂号"),

    // feedback 6xxx
    RECORD_NOT_FOUND(6000, "导诊记录不存在"),
    RECORD_ALREADY_REGISTERED(6001, "该导诊记录已完成挂号"),

    // llm 7xxx（LLM 适配层：DeepSeek 对话 / 阿里 embedding / 阿里 rerank）
    LLM_NOT_CONFIGURED(7000, "模型服务未配置，请在 application-local.yml 填写 API Key"),
    LLM_CALL_FAILED(7001, "模型调用失败，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
