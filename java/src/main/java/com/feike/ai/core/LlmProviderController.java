package com.feike.ai.core;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列出可切换的 LLM Provider，供 playground 下拉框使用。
 */
@RestController
@RequestMapping("/providers")
public class LlmProviderController {

    private final LlmProviderRegistry registry;

    /**
     * @param registry 读取 app.ai.providers
     */
    public LlmProviderController(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回默认 Provider 与清单。
     *
     * @return defaultProvider 与 providers 列表
     */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultProvider", registry.defaultProviderId());
        body.put("providers", registry.list());
        return body;
    }
}
