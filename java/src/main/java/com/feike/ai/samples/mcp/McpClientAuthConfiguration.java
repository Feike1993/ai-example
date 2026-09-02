package com.feike.ai.samples.mcp;

import com.feike.ai.core.AiProperties;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * 为 Streamable HTTP MCP Client 注入 Authorization Bearer。
 * <p>
 * 通过 {@link McpClientCustomizer} 挂到 transport Builder；inprocess 不走此传输。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientAuthConfiguration {

    /**
     * 在每次远端 HTTP 请求上设置 Bearer（与 mcp-server 共享密钥）。
     *
     * @param properties 读取 {@code app.ai.mcp.remote-bearer-token}
     * @return transport 级 customizer
     */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpBearerTransportCustomizer(
        AiProperties properties
    ) {
        McpSyncHttpClientRequestCustomizer requestCustomizer =
            bearerRequestCustomizer(properties.mcp().remoteBearerToken());
        return (name, transportBuilder) -> transportBuilder.httpRequestCustomizer(requestCustomizer);
    }

    /**
     * 构造写入 Authorization 的请求定制器（单测可直接调用）。
     *
     * @param token Bearer 明文
     * @return sync request customizer
     */
    static McpSyncHttpClientRequestCustomizer bearerRequestCustomizer(String token) {
        String normalized = token == null ? "" : token.trim();
        return (builder, method, endpoint, body, context) ->
            builder.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + normalized);
    }
}
