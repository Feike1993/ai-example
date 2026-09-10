package com.feike.ai.production.chat.model;

/**
 * 会话锁探针请求。不调 LLM，只占用 Redis 会话锁一段时间。
 *
 * @param holdMs 持锁毫秒；缺省 3000，上限 10000
 */
public record LockProbeQuery(Integer holdMs) {}
