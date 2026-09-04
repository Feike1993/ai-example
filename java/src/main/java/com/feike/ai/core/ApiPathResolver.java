package com.feike.ai.core;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.net.Proxy;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 构造 OpenAI 兼容 HTTP 客户端，并统一补齐 {@code /v1}。
 * <p>
 * DashScope 等网关常自带版本段，Ollama 类本地服务通常不带，这里统一处理。
 */
public final class ApiPathResolver {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 流式补全可能持续数分钟，读超时不能按普通 REST 的几秒来设。 */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    private ApiPathResolver() {}

    /**
     * 按 baseUrl / apiKey 创建官方 OpenAI Java 客户端（Spring AI 2.0 底层依赖）。
     *
     * @param baseUrl     网关根地址，可省略末尾 {@code /v1}
     * @param apiKey      Bearer Token
     * @param bypassProxy 为 true 时强制直连，忽略 JVM {@code https.proxyHost}
     * @return 可用于 {@code OpenAiChatModel} 的同步客户端
     */
    public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey, boolean bypassProxy) {
        Timeout timeout = Timeout.builder()
            .connect(DEFAULT_CONNECT_TIMEOUT)
            .read(DEFAULT_READ_TIMEOUT)
            .build();
        // 为何可按 Provider 关代理：IDEA 常设 https.proxyHost；公网域名若解析到 RFC1918，
        // nonProxyHosts 只匹配主机名不匹配解析后 IP，请求会进代理并在 connect 超时（约 10s）失败。
        // true: 忽略 JVM 代理设置，直连 false: 按 JVM 代理设置代理
        var httpBuilder = SpringAiOpenAiHttpClient.builder().timeout(timeout);
        if (bypassProxy) {
            httpBuilder.proxy(Proxy.NO_PROXY);
        }
        ClientOptions options = ClientOptions.Companion.builder()
            .apiKey(apiKey)
            .credential(BearerTokenCredential.create(apiKey))
            .baseUrl(resolveVersionedBaseUrl(baseUrl))
            .timeout(timeout)
            .httpClient(httpBuilder.build())
            .build();
        return new OpenAIClientImpl(options);
    }

    /**
     * 默认遵循 JVM 代理设置（与历史行为一致）。
     *
     * @param baseUrl 网关根地址
     * @param apiKey  Bearer Token
     * @return 同步客户端
     */
    public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
        return buildOpenAiClient(baseUrl, apiKey, false);
    }

    /**
     * 若 baseUrl 末尾没有 {@code /v1} 这类版本段则补上，避免各厂商路径不一致。
     *
     * @param baseUrl 原始地址，允许末尾斜杠
     * @return 带版本段、无尾斜杠的地址
     */
    public static String resolveVersionedBaseUrl(String baseUrl) {
        String stripped = stripTrailingSlashes(baseUrl);
        if (baseUrlContainsVersion(stripped)) {
            return stripped;
        }
        return stripped + "/v1";
    }

    /**
     * 判断地址末尾是否已包含 {@code /v1}、{@code /v1beta} 等版本段。
     *
     * @param baseUrl 待检测地址，空值视为无版本段
     * @return 已带版本段时为 {@code true}
     */
    public static boolean baseUrlContainsVersion(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        return TRAILING_VERSION.matcher(stripTrailingSlashes(baseUrl.trim())).find();
    }

    /**
     * 去掉末尾多余 {@code /}，便于拼接和正则匹配。
     *
     * @param value 原始字符串，{@code null} 视为空串
     * @return 去尾斜杠后的字符串
     */
    public static String stripTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
