package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.dao.SecretResolver;

import com.feike.ai.core.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Map;

/**
 * 首次启动把环境变量里的 LLM Key 和随机 JWT HMAC <b>加密写入一次</b>。
 * <p>
 * 表里已经有行就不再用 env 覆盖：否则每次重启都会把库内已轮换的密钥打回旧值。
 * 任何失败只打日志——缺密钥时接口 503，不能拖垮整个进程。
 */
public class SecretBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretBootstrap.class);

    private final SecretResolver secrets;
    private final AiProperties aiProperties;

    /**
     * @param secrets      密钥存储
     * @param aiProperties 教学侧仍从 env 读的 Provider 配置，用作首次灌入
     */
    public SecretBootstrap(SecretResolver secrets, AiProperties aiProperties) {
        this.secrets = secrets;
        this.aiProperties = aiProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!secrets.available()) {
            log.warn("跳过密钥灌入：SecretResolver 不可用");
            return;
        }
        try {
            seedJwtHmac();
            seedLlmKeys();
        } catch (RuntimeException ex) {
            log.warn("密钥灌入失败，工业级接口可能 503: {}", ex.toString());
        }
    }

    private void seedJwtHmac() {
        if (secrets.contains(SecretResolver.JWT_HMAC)) {
            return;
        }
        secrets.put(SecretResolver.JWT_HMAC, EnvelopeCrypto.randomKeyBase64());
        log.info("已生成并封存 jwt.hmac（只写一次）");
    }

    private void seedLlmKeys() {
        Map<String, AiProperties.Provider> providers = aiProperties.providers();
        if (providers == null) {
            return;
        }
        for (Map.Entry<String, AiProperties.Provider> entry : providers.entrySet()) {
            String name = SecretResolver.llmKey(entry.getKey());
            if (secrets.contains(name)) {
                continue;
            }
            String apiKey = entry.getValue() == null ? null : entry.getValue().apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                continue;
            }
            secrets.put(name, apiKey);
            log.info("已将 Provider '{}' 的 API Key 封存进 prod_secret", entry.getKey());
        }
    }
}
