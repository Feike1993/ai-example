package com.feike.ai.production.web;

import org.springframework.http.HttpStatus;

/**
 * 工业级失败码目录：稳定机器码 + HTTP 状态 + 默认中文。
 * <p>
 * 给用户看的只有 {@link #getDefaultMessage()}；{@link #getCode()} 与 SSE {@code error} 事件对齐，
 * 方便排障，不替代中文主文案。成功响应不走这里。
 */
public enum ErrorCodeEnum {

    AUTH_MISSING_TOKEN("auth_missing_token", HttpStatus.UNAUTHORIZED, "缺少 Authorization: Bearer 令牌"),
    AUTH_INVALID("auth_invalid", HttpStatus.UNAUTHORIZED, "令牌无效或已过期"),
    AUTH_LOGIN_FAILED("auth_login_failed", HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    SECRET_UNAVAILABLE("secret_unavailable", HttpStatus.SERVICE_UNAVAILABLE, "密钥暂不可用，请稍后重试"),
    INGEST_FORBIDDEN("ingest_forbidden", HttpStatus.FORBIDDEN, "重建索引需要 ADMIN 角色"),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "禁止访问"),
    SESSION_NOT_FOUND("session_not_found", HttpStatus.NOT_FOUND, "会话不存在"),
    SESSION_BUSY("session_busy", HttpStatus.CONFLICT, "会话已有一轮对话正在进行，请稍后再试"),
    SESSION_DISABLED("session_disabled", HttpStatus.NOT_IMPLEMENTED, "多轮会话未启用"),
    RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict", HttpStatus.CONFLICT, "Idempotency-Key 已用于不同的请求体"),
    CONFLICT("conflict", HttpStatus.CONFLICT, "请求冲突"),
    INPUT_DENY("input_deny", HttpStatus.UNPROCESSABLE_ENTITY, "输入命中敏感词，已拒绝处理"),
    OUTPUT_DENY("output_deny", HttpStatus.UNPROCESSABLE_ENTITY, "输出命中敏感词，已拦截"),
    CITATION_REQUIRED("citation_required", HttpStatus.UNPROCESSABLE_ENTITY, "回答缺少有效引用，已拒绝展示"),
    BAD_REQUEST("bad_request", HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNKNOWN_PROVIDER("unknown_provider", HttpStatus.BAD_REQUEST, "未知 LLM Provider"),
    RUN_GONE("run_gone", HttpStatus.GONE, "run 不存在或已超出事件保留窗口，请重新发起请求"),
    AGENT_NOT_ASSEMBLED("agent_not_assembled", HttpStatus.NOT_IMPLEMENTED, "Agent 未装配"),
    INGEST_FAILED("ingest_failed", HttpStatus.INTERNAL_SERVER_ERROR, "读取语料失败"),
    EVENT_LOG_UNAVAILABLE("event_log_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
        "事件日志暂不可用（Redis 连接异常），工业级流式接口无法保证断线续传"),
    INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCodeEnum(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /**
     * @return 稳定机器码，写入 JSON {@code code}
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 对应 HTTP 状态；工业级保留真实 4xx/5xx，不伪装成 200
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * @return 给用户看的默认简体中文
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }
}
