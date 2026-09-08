package com.feike.ai.production.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 本次部署没有开启多轮会话。
 * <p>
 * 用 501 而不是 404 或 500：路由是存在的，只是这套部署没装配会话能力，
 * 重试和换参数都没用，得改配置。消息里直接点出配置项，省掉一轮翻源码。
 */
@ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
public class SessionDisabledException extends RuntimeException {

    public SessionDisabledException() {
        super("多轮会话未启用：需要 app.production.session.enabled=true，"
            + "且 Flyway 已建好 prod_chat_session / prod_chat_message 两张表");
    }
}
