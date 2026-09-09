package com.feike.ai.samples.chat.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Chat 样例请求。
 *
 * @param prompt      用户问题，不能为空
 * @param temperature 可选采样温度
 * @param provider    可选 LLM Provider id，空则用默认 DeepSeek
 */
public record ChatRequestDTO(@NotBlank String prompt, Double temperature, String provider) {}
