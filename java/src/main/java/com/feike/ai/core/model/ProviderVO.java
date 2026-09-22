package com.feike.ai.core.model;

import java.util.List;

/**
 * 前端 / 索引用的 Provider 摘要，不含密钥。
 *
 * @param id         配置 key
 * @param label      展示名
 * @param model      当前模型
 * @param configured 是否已填 API Key
 * @param capabilities 此条模型可承担的能力；只用于目录展示与前端筛选
 */
public record ProviderVO(String id, String label, String model, boolean configured, List<String> capabilities) {
    public ProviderVO(String id, String label, String model, boolean configured) {
        this(id, label, model, configured, List.of("chat"));
    }
}
