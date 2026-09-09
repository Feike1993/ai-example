package com.feike.ai.samples.chat.model;

import com.feike.ai.core.model.TokenUsageDTO;

/**
 * Chat 样例服务层结果。
 *
 * @param content 模型完整回复
 * @param usage   网关返回的用量，未上报时为 {@code null}
 */
public record ChatDTO(String content, TokenUsageDTO usage) {}
