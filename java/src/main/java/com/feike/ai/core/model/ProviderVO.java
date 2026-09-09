package com.feike.ai.core.model;

/**
 * 前端 / 索引用的 Provider 摘要，不含密钥。
 *
 * @param id         配置 key
 * @param label      展示名
 * @param model      当前模型
 * @param configured 是否已填 API Key
 */
public record ProviderVO(String id, String label, String model, boolean configured) {}
