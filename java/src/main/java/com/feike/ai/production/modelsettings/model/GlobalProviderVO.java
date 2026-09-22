package com.feike.ai.production.modelsettings.model;

import java.util.List;

/** 管理页 Provider 摘要；刻意不包含 API Key。 */
public record GlobalProviderVO(
    String id, String label, String baseUrl, String model, List<String> models,
    List<String> capabilities, Double temperature, Boolean enableThinking,
    boolean bypassProxy, boolean keyConfigured
) {}
