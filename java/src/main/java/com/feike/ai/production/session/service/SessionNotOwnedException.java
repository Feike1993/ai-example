package com.feike.ai.production.session.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 会话不属于当前租户。对外一律 404，避免靠 403 探测别人的 sessionId。
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SessionNotOwnedException extends RuntimeException {

    /**
     * @param sessionId 会话 id
     */
    public SessionNotOwnedException(String sessionId) {
        super("会话不存在");
    }
}
