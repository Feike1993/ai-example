package com.feike.ai.production.speech.manager;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.dao.SecretResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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
        DashScopeSpeechClient client = new DashScopeSpeechClient(
            ai(), secrets, new ProductionProperties(true, "c", 4, 400, 1, true, 60, 4,
                null, null, null, null, null, null, null, null), JsonMapper.builder().build(), http);

        assertArrayEquals(new byte[] {1, 2, 3}, client.speak("你好"));
        var requests = org.mockito.Mockito.mockingDetails(http).getInvocations().stream()
            .map(invocation -> (HttpRequest) invocation.getArgument(0))
            .toList();
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            requests.getFirst().uri().toString());
        assertEquals("https://audio.example.test/result.wav", requests.get(1).uri().toString());
    }

    private static AiProperties ai() {
        return new AiProperties("dashscope", null, Map.of("dashscope", new AiProperties.Provider(
            "DashScope", "https://dashscope.aliyuncs.com/compatible-mode", "", "qwen3.8-max",
            null, null, null, List.of("tts")
        )), null, null, null, null, null, null, null, null, null, null);
    }
}
