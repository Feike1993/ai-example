package com.feike.ai.production.secret.dao;

import com.feike.ai.production.secret.service.SecretUnavailableException;

import java.util.Optional;

/**
 * 工业级密钥读取口。
 * <p>
 * 实现分 postgres（密文表 + KEK）和 env（单测 / 无库调试）。调用方只认名字，
 * 不关心密文怎么存。{@link #available()} 为 false 时接口应 503，而不是在构造期炸掉。
 */
public interface SecretResolver {

    /** JWT HMAC 的逻辑名。 */
    String JWT_HMAC = "jwt.hmac";

    /**
     * LLM Provider 密钥名，如 {@code llm.deepseek}。
     *
     * @param providerId Provider id
     * @return 逻辑名
     */
    static String llmKey(String providerId) {
        return "llm." + providerId;
    }

    /**
     * @return 当前能否解密读取；KEK 缺失或存储不可达时为 false
     */
    boolean available();

    /**
     * 读取明文。
     *
     * @param name 逻辑名
     * @return 明文；不存在为空
     * @throws SecretUnavailableException 存储或 KEK 不可用
     */
    Optional<String> get(String name);

    /**
     * 写入或覆盖。postgres 实现会加密；env 实现只放内存。
     *
     * @param name      逻辑名
     * @param plaintext 明文
     * @throws SecretUnavailableException 无法写入
     */
    void put(String name, String plaintext);

    /**
     * @param name 逻辑名
     * @return 是否已有该行
     */
    boolean contains(String name);

    /**
     * 必有项，否则 503。
     *
     * @param name 逻辑名
     * @return 明文
     */
    default String require(String name) {
        return get(name).orElseThrow(() ->
            new SecretUnavailableException("缺少密钥: " + name));
    }
}
