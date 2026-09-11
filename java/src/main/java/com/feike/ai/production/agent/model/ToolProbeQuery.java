package com.feike.ai.production.agent.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 无 LLM 的工具策略探针。不执行工具。
 *
 * @param tool 工具名，例如 rebuild_index
 */
public record ToolProbeQuery(@NotBlank(message = "工具名不能为空") String tool) {}
