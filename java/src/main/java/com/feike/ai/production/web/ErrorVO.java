package com.feike.ai.production.web;

/**
 * 工业级失败体。只含稳定机器码和给用户看的中文，不含堆栈、path、timestamp。
 *
 * @param code    与 SSE {@code error} 事件对齐的机器码
 * @param message 简体中文业务句
 */
public record ErrorVO(String code, String message) {}
