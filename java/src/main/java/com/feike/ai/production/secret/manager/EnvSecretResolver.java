package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.dao.SecretResolver;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内密钥表，给单测和无库调试用。
 * <p>
 * 明文只活在内存里，不写磁盘。生产默认不要走这条路径。
 */
public class EnvSecretResolver implements SecretResolver {

    private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<String> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(name));
    }

    @Override
    public void put(String name, String plaintext) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("密钥名不能为空");
        }
        values.put(name, plaintext == null ? "" : plaintext);
    }

    @Override
    public boolean contains(String name) {
        return name != null && values.containsKey(name);
    }
}
