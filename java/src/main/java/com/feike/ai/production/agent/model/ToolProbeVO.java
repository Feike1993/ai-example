package com.feike.ai.production.agent.model;

/**
 * 工具策略探针结果。
 *
 * @param tool    规范化后的工具名
 * @param allowed 当前角色是否允许
 * @param denied  与 allowed 相反，方便和 SSE step.denied 对齐
 */
public record ToolProbeVO(String tool, boolean allowed, boolean denied) {}
