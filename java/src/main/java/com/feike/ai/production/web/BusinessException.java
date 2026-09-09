package com.feike.ai.production.web;

/**
 * 工业级业务失败：携带 {@link ErrorCodeEnum}，由 {@link ProductionExceptionHandler} 写成 {@code {code,message}}。
 * <p>
 * 可覆盖默认中文，但不能改机器码和 HTTP 状态——那两样要和前端、SSE 对齐。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCodeEnum errorCode;

    /**
     * 使用目录里的默认中文。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCodeEnum errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * 覆盖给用户看的中文，机器码与 HTTP 状态仍以目录为准。
     *
     * @param errorCode 错误码
     * @param message   面向用户的说明
     */
    public BusinessException(ErrorCodeEnum errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @return 错误码目录项
     */
    public ErrorCodeEnum getErrorCode() {
        return errorCode;
    }
}
