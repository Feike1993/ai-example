package com.feike.ai.production.secret.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 模型密钥不可用：缺 KEK、解密失败或库读不到密文。
 * <p>
 * 映射 503 而不是拖垮启动；教学场和工业场的真实模型调用均使用这套密钥。
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SecretUnavailableException extends RuntimeException {

    /**
     * @param message 面向运维的原因
     */
    public SecretUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message 面向运维的原因
     * @param cause   根因
     */
    public SecretUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
