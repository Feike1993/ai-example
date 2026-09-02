package com.feike.ai.samples.mcp;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Client Bearer：请求 customizer 写入 Authorization。
 */
@DisplayName("McpClientAuthConfiguration")
class McpClientAuthConfigurationTest {

    @Test
    void bearerRequestCustomizerShouldSetAuthorizationHeader() {
        McpSyncHttpClientRequestCustomizer customizer =
            McpClientAuthConfiguration.bearerRequestCustomizer("secret-token");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:8081/mcp"));
        customizer.customize(builder, "POST", URI.create("http://localhost:8081/mcp"), "{}", McpTransportContext.EMPTY);
        HttpRequest request = builder.GET().build();
        assertEquals("Bearer secret-token", request.headers().firstValue(HttpHeaders.AUTHORIZATION).orElse(""));
    }
}
