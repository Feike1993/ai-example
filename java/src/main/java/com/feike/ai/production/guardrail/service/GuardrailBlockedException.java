package com.feike.ai.production.guardrail.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 护栏拦截：输入词表、输出词表或引用校验失败。
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GuardrailBlockedException extends RuntimeException {

    private final String code;

    /**
     * @param code    机器可读阶段名
     * @param message 面向用户的说明
     */
    public GuardrailBlockedException(String code, String message) {
        super(message);
        this.code = code == null ? "guardrail_blocked" : code;
    }

    /**
     * @return 错误码
     */
    public String code() {
        return code;
    }
}
