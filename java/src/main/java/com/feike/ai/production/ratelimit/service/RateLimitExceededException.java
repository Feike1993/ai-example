package com.feike.ai.production.ratelimit.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 触发令牌桶上限。
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final int remaining;

    /**
     * @param retryAfterSeconds 建议等待秒数
     * @param remaining         剩余令牌（此时为 0）
     */
    public RateLimitExceededException(long retryAfterSeconds, int remaining) {
        super("请求过于频繁，请稍后重试");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.remaining = remaining;
    }

    /**
     * @return Retry-After 秒
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * @return 剩余令牌
     */
    public int remaining() {
        return remaining;
    }
}
