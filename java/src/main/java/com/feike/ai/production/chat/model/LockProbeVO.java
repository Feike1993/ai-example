package com.feike.ai.production.chat.model;

/**
 * 会话锁探针结果，用于多实例对照。
 *
 * @param sessionId  实际使用的会话 id
 * @param instanceId 持锁的这一进程
 * @param heldMs     实际持锁毫秒
 */
public record LockProbeVO(String sessionId, String instanceId, int heldMs) {}
