package com.feike.ai.production.speech.manager;

import com.feike.ai.production.modelsettings.model.GlobalProviderVO;
import com.feike.ai.production.modelsettings.model.ModelRouteVO;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.dao.SecretResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashScopeSpeechClientTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void qwenTtsShouldUseNativeGenerationEndpointAndDownloadAudioUrl() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> generation = mock(HttpResponse.class);
        HttpResponse<byte[]> audio = mock(HttpResponse.class);
        when(generation.statusCode()).thenReturn(200);
        when(generation.body()).thenReturn("""
            {"output":{"audio":{"url":"https://audio.example.test/result.wav"}}}
            """);
        when(audio.statusCode()).thenReturn(200);
        when(audio.body()).thenReturn(new byte[] {1, 2, 3});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn((HttpResponse) generation, (HttpResponse) audio);

        SecretResolver secrets = mock(SecretResolver.class);
        when(secrets.available()).thenReturn(true);
        when(secrets.get("llm.dashscope")).thenReturn(Optional.of("test-key"));
        GlobalModelSettingsService settings = mock(GlobalModelSettingsService.class);
        when(settings.route("tts")).thenReturn(new ModelRouteVO(
            "tts", "dashscope", "qwen3-tts-flash", "Cherry"));
        when(settings.provider("dashscope")).thenReturn(new GlobalProviderVO(
            "dashscope", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode",
            "qwen3.8-max", List.of("qwen3.8-max", "qwen3-tts-flash"), List.of("chat", "tts"),
            0.2, null, false, true));
        DashScopeSpeechClient client = new DashScopeSpeechClient(
            secrets, JsonMapper.builder().build(), http, settings);

        assertArrayEquals(new byte[] {1, 2, 3}, client.speak("你好"));
        var requests = org.mockito.Mockito.mockingDetails(http).getInvocations().stream()
            .map(invocation -> (HttpRequest) invocation.getArgument(0))
            .toList();
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            requests.getFirst().uri().toString());
        assertEquals("https://audio.example.test/result.wav", requests.get(1).uri().toString());
    }
}
