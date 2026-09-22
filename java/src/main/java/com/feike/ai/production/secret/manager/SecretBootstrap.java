package com.feike.ai.production.secret.manager;

import com.feike.ai.production.secret.dao.SecretResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 首次启动生成随机 JWT HMAC 并<b>加密写入一次</b>。
 * <p>
 * LLM Key 只能经“模型与服务设置”写入，不再从教学侧 YAML/环境变量灌入。
 * 任何失败只打日志——缺密钥时接口 503，不能拖垮整个进程。
 */
public class SecretBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretBootstrap.class);

    private final SecretResolver secrets;

    public SecretBootstrap(SecretResolver secrets) {
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!secrets.available()) {
            log.warn("跳过密钥灌入：SecretResolver 不可用");
            return;
        }
        try {
            seedJwtHmac();
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
}
