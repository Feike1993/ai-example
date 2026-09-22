package com.feike.ai.production.speech.manager;

import com.feike.ai.core.ApiPathResolver;
import com.feike.ai.core.config.AiProperties;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.modelsettings.model.ModelRouteVO;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 手写 DashScope compatible-mode 的 ASR / TTS，不打开 Spring AI Audio 自动配置。
 * <p>
 * Key 与 Chat 同源信封 {@code llm.dashscope}；失败只抛中文业务句，不把上游 JSON 原样甩给浏览器。
 */
public class DashScopeSpeechClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final AiProperties aiProperties;
    private final SecretResolver secrets;
    private final ProductionProperties.Media media;
    private final HttpClient http;
    private final JsonMapper jsonMapper;
    private final GlobalModelSettingsService settings;

    /**
     * @param aiProperties baseUrl
     * @param secrets      信封 Key
     * @param properties   模型与音色
     * @param jsonMapper   解析错误体
     */
    public DashScopeSpeechClient(
        AiProperties aiProperties,
        SecretResolver secrets,
        ProductionProperties properties,
        JsonMapper jsonMapper
    ) {
        this(aiProperties, secrets, properties, jsonMapper,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /**
     * @param http 可注入的客户端，便于单测
     */
    public DashScopeSpeechClient(
        AiProperties aiProperties,
        SecretResolver secrets,
        ProductionProperties properties,
        JsonMapper jsonMapper,
        HttpClient http
    ) {
        this(aiProperties, secrets, properties, jsonMapper, http, null);
    }

    public DashScopeSpeechClient(AiProperties aiProperties, SecretResolver secrets, ProductionProperties properties, JsonMapper jsonMapper, HttpClient http, GlobalModelSettingsService settings) {
        this.aiProperties = aiProperties;
        this.secrets = secrets;
        this.media = properties.media();
        this.jsonMapper = jsonMapper;
        this.http = http;
        this.settings = settings;
    }

    /**
     * OpenAI 兼容 {@code /audio/transcriptions}。
     *
     * @param audio    音频字节
     * @param mime     归一化 mime
     * @param filename 表单文件名
     * @return 转写文本
     */
    public String transcribe(byte[] audio, String mime, String filename) {
        String boundary = "----feike" + UUID.randomUUID().toString().replace("-", "");
        ModelRouteVO route = route("asr", media.asrProvider(), media.asrModel());
        byte[] body = multipart(boundary, audio, mime, filename == null ? "audio.webm" : filename, route.model());
        HttpRequest request = authorized(route.providerId(), "/audio/transcriptions")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<String> response = sendText(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw speechFailed("语音识别失败", response.body());
        }
        try {
            JsonNode root = jsonMapper.readTree(response.body());
            JsonNode text = root.get("text");
            return text == null || text.isNull() ? "" : text.asText("");
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCodeEnum.SPEECH_FAILED, "语音识别失败，请稍后重试");
        }
    }

    /**
     * OpenAI 兼容 {@code /audio/speech}。
     *
     * @param text 朗读文本
     * @return 音频字节
     */
    public byte[] speak(String text) {
        ModelRouteVO route = route("tts", media.ttsProvider(), media.ttsModel());
        Map<String, String> payload = Map.of(
            "model", route.model(),
            "input", text,
            "voice", media.ttsVoice()
        );
        byte[] json = jsonMapper.writeValueAsBytes(payload);
        HttpRequest request = authorized(route.providerId(), "/audio/speech")
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json))
            .build();
        HttpResponse<byte[]> response = sendBytes(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String err = new String(response.body() == null ? new byte[0] : response.body(), StandardCharsets.UTF_8);
            throw speechFailed("语音合成失败", err);
        }
        byte[] audio = response.body();
        if (audio == null || audio.length == 0) {
            throw new BusinessException(ErrorCodeEnum.SPEECH_FAILED, "语音合成失败，请稍后重试");
        }
        return audio;
    }

    private HttpRequest.Builder authorized(String providerId, String path) {
        if (!secrets.available()) {
            throw new SecretUnavailableException("工业级密钥不可用，无法调用语音接口");
        }
        AiProperties.Provider cfg = aiProperties.providers().get(providerId);
        if (cfg == null) {
            throw new BusinessException(ErrorCodeEnum.UNKNOWN_PROVIDER, "未知 LLM Provider: " + providerId);
        }
        String apiKey = secrets.get(SecretResolver.llmKey(providerId)).orElse("");
        if (apiKey.isBlank()) {
            throw new SecretUnavailableException("prod_secret 中没有 llm." + providerId);
        }
        String base = ApiPathResolver.resolveVersionedBaseUrl(cfg.baseUrl());
        return HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey);
    }

    private ModelRouteVO route(String capability, String fallbackProvider, String fallbackModel) {
        ModelRouteVO route = settings == null ? null : settings.route(capability);
        return route == null ? new ModelRouteVO(capability, fallbackProvider, fallbackModel, null) : route;
    }

    private HttpResponse<String> sendText(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCodeEnum.SPEECH_FAILED, "语音识别被中断");
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodeEnum.SPEECH_FAILED, "语音识别失败，请稍后重试");
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCodeEnum.SPEECH_FAILED, "语音合成被中断");
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodeEnum.SPEECH_FAILED, "语音合成失败，请稍后重试");
        }
    }

    private byte[] multipart(String boundary, byte[] audio, String mime, String filename, String model) {
        String dash = "--" + boundary;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out, dash + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            write(out, model + "\r\n");
            write(out, dash + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + sanitizeFilename(filename) + "\"\r\n");
            write(out, "Content-Type: " + mime + "\r\n\r\n");
            out.write(audio);
            write(out, "\r\n" + dash + "--\r\n");
        } catch (IOException ex) {
            throw new IllegalStateException("组装 multipart 失败", ex);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitizeFilename(String filename) {
        String trimmed = filename.replace("\"", "").replace("\r", "").replace("\n", "");
        return trimmed.isBlank() ? "audio.webm" : trimmed;
    }

    private BusinessException speechFailed(String prefix, String body) {
        String detail = extractErrorMessage(body);
        String message = detail == null || detail.isBlank() ? prefix + "，请稍后重试" : prefix + "：" + detail;
        return new BusinessException(ErrorCodeEnum.SPEECH_FAILED, message);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank() || looksBinary(body)) {
            return null;
        }
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode error = root.get("error");
            if (error != null && error.has("message")) {
                return error.get("message").asText("");
            }
            if (root.has("message")) {
                return root.get("message").asText("");
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static boolean looksBinary(String body) {
        for (int i = 0; i < Math.min(body.length(), 32); i++) {
            char c = body.charAt(i);
            if (c == 0) {
                return true;
            }
        }
        return false;
    }
}
