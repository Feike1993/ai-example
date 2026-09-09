package com.feike.ai.production.session.service;

/**
 * 连续多次取号都撞上唯一约束，放弃写入。
 * <p>
 * 单独定型而不是把 {@code DuplicateKeyException} 直接抛出去：调用方需要区分
 * 「这轮没存下来但回答已经发给用户了」和其他数据库错误——前者可以只记日志继续，
 * 后者通常意味着 schema 或连接出了问题。
 */
public class SessionWriteConflictException extends RuntimeException {

    private final String sessionId;

    /**
     * @param sessionId 冲突的会话
     * @param attempts  已尝试次数
     * @param cause     最后一次的主键冲突
     */
    public SessionWriteConflictException(String sessionId, int attempts, Throwable cause) {
        super("会话 " + sessionId + " 连续 " + attempts + " 次写入取号冲突", cause);
        this.sessionId = sessionId;
    }

    /**
     * @return 冲突的会话 id
     */
    public String sessionId() {
        return sessionId;
    }
}
