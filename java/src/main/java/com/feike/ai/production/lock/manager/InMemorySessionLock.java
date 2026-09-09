package com.feike.ai.production.lock.manager;

import com.feike.ai.samples.context.dao.impl.JdbcChatSessionDAOImpl;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内实现，仅用于单实例演示与单元测试。
 * <p>
 * 多实例部署下它等于没有锁：每个 JVM 各持一份集合，同一会话在两个实例上都能拿到。
 * 这正是教学版 {@code JdbcChatSessionDAOImpl} 用 {@code synchronized} 的问题所在，
 * 保留这个实现是为了让「换成 Redis 之后差别在哪」可以直接对照跑。
 */
public class InMemorySessionLock implements SessionLock {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<Handle> tryAcquire(String sessionId) {
        if (!held.add(sessionId)) {
            return Optional.empty();
        }
        AtomicBoolean released = new AtomicBoolean(false);
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) {
                held.remove(sessionId);
            }
        });
    }
}
