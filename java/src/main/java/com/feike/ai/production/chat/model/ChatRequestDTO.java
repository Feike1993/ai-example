package com.feike.ai.production.chat.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 工业级问答请求体。
 *
 * @param question        用户问题
 * @param sessionId       会话 id；空则新建，实际使用的 id 在响应里回传
 * @param provider        Chat Provider id；空则用默认
 * @param topK            覆盖默认 topK
 * @param queryExpansion  none / rewrite / hyde；空则用配置默认（通常 none）
 */
public record ChatRequestDTO(
    @NotBlank(message = "问题不能为空")
    @Size(max = MAX_QUESTION_CHARS, message = "问题过长")
    String question,
    String sessionId,
    String provider,
    @Min(1) @Max(MAX_TOP_K) Integer topK,
    String queryExpansion
) {
    /** 问句最大字符数，GET/POST 共用，避免超长 prompt 打满上下文。 */
    public static final int MAX_QUESTION_CHARS = 4000;

    /** 请求侧 topK 上限，检索层再 clamp 一次。 */
    public static final int MAX_TOP_K = 32;
}
