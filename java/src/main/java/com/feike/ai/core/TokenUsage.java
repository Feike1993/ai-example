package com.feike.ai.core;

/**
 * 同步 LLM 调用的 token 用量；网关未返回时各字段为 {@code null}。
 *
 * @param prompt     输入 token 数
 * @param completion 输出 token 数
 * @param total      合计 token 数
 */
public record TokenUsage(Integer prompt, Integer completion, Integer total) {}
