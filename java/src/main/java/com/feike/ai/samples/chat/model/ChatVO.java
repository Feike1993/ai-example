package com.feike.ai.samples.chat.model;

import com.feike.ai.core.model.TokenUsageDTO;

/**
 * Chat 样例 HTTP 展示。
 *
 * @param content 模型完整回复
 * @param usage   token 用量，网关未返回时为 {@code null}
 */
public record ChatVO(String content, TokenUsageDTO usage) {}
