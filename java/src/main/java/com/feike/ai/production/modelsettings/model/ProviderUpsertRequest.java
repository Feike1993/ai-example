package com.feike.ai.production.modelsettings.model;

import java.util.List;

/** API Key 为 write-only；响应模型不含该字段。 */
public record ProviderUpsertRequest(
    String label, String baseUrl, String model, List<String> capabilities, String apiKey
) {}
