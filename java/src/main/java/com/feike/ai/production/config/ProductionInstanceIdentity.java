package com.feike.ai.production.config;

/**
 * 本进程在多实例部署里的短名，写入 {@code X-Instance-Id}。
 * <p>
 * 来自 {@code PRODUCTION_INSTANCE_ID}；Compose 里 java-a / java-b 各写一份。
 * 本机 {@code bootRun} 未设置时用 {@code local}，避免把主机名泄漏到响应头。
 *
 * @param id 短名，如 {@code java-a}
 */
public record ProductionInstanceIdentity(String id) {

    public ProductionInstanceIdentity {
        if (id == null || id.isBlank()) {
            id = "local";
        }
    }
}
