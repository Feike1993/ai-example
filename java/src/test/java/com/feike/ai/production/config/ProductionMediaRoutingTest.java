package com.feike.ai.production.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证视觉、ASR、TTS 不再必须共用同一个 Provider。 */
class ProductionMediaRoutingTest {

    @Test
    void shouldKeepDedicatedSpeechProviders() {
        ProductionProperties.Media media = new ProductionProperties.Media(
            1024L, List.of("image/png"), List.of("audio/mpeg"), "vision-v1", "vision-gateway",
            "asr-v1", "asr-gateway", "tts-v1", "tts-gateway", "Cherry", 200,
            3, List.of("application/pdf"), 2, 8000, 3, 20
        );

        assertEquals("vision-gateway", media.visionProvider());
        assertEquals("asr-gateway", media.asrProvider());
        assertEquals("tts-gateway", media.ttsProvider());
    }

    @Test
    void shouldFallbackSpeechProvidersToVisionForLegacyConfiguration() {
        ProductionProperties.Media media = new ProductionProperties.Media(
            1024L, List.of("image/png"), List.of("audio/mpeg"), "vision-v1", "dashscope",
            "asr-v1", "tts-v1", "Cherry", 200, 3, List.of("application/pdf"), 2, 8000, 3, 20
        );

        assertEquals("dashscope", media.asrProvider());
        assertEquals("dashscope", media.ttsProvider());
    }
}
