package com.feike.ai.production.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 同一会话已有一轮对话在进行。
 * <p>
 * 用 409 而不是 429：这不是限流，重试也不会因为「等久一点」就变好，
 * 必须等前一轮结束。语义上是资源状态冲突。
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class SessionBusyException extends RuntimeException {

    /**
     * @param sessionId 被占用的会话 id
     */
    public SessionBusyException(String sessionId) {
        super("会话 " + sessionId + " 已有一轮对话正在进行，请等待其完成后再试");
    }
}
