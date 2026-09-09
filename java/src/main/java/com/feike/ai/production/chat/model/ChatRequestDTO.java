package com.feike.ai.production.chat.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 工业级问答请求体。
 *
 * @param question  用户问题
 * @param sessionId 会话 id；空则新建，实际使用的 id 在响应里回传
 * @param provider  Chat Provider id；空则用默认
 * @param topK      覆盖默认 topK
 */
public record ChatRequestDTO(
    @NotBlank String question,
    String sessionId,
    String provider,
    Integer topK
) {}
