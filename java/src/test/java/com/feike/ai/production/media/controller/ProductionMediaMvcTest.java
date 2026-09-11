package com.feike.ai.production.media.controller;

import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.controller.ProductionAuthController;
import com.feike.ai.production.auth.manager.DemoUserService;
import com.feike.ai.production.auth.manager.JwtService;
import com.feike.ai.production.chat.controller.ProductionChatController;
import com.feike.ai.production.chat.model.ChatAttachments;
import com.feike.ai.production.chat.service.ProductionChatService;
import com.feike.ai.production.chat.service.impl.ProductionChatServiceImpl;
import com.feike.ai.production.config.ProductionInstanceIdentity;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.lock.manager.InMemorySessionLock;
import com.feike.ai.production.media.manager.ProductionDocumentFixtures;
import com.feike.ai.production.media.manager.ProductionMediaInspector;
import com.feike.ai.production.media.service.impl.ProductionMediaServiceImpl;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.generate.service.ProductionAnswerGenerator;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.manager.EnvelopeCrypto;
import com.feike.ai.production.secret.manager.EnvSecretResolver;
import com.feike.ai.production.session.dao.impl.FakeProductionChatSessionDAOImpl;
import com.feike.ai.production.speech.manager.DashScopeSpeechClient;
import com.feike.ai.production.speech.service.impl.ProductionSpeechServiceImpl;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.service.SseRunExecutor;
import com.feike.ai.production.web.ProductionExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体探针与语音入口：鉴权 / mime / 体积，默认不打网关。
 */
@DisplayName("Production media MVC")
class ProductionMediaMvcTest {

    private static final byte[] TINY_JPEG = ProductionMediaInspectorTestJpeg.BYTES;

    private final ProductionRetrievalService retrieval = mock(ProductionRetrievalService.class);
    private final ProductionAnswerGenerator generator = mock(ProductionAnswerGenerator.class);
    private final DashScopeSpeechClient speechClient = mock(DashScopeSpeechClient.class);

    private EnvSecretResolver secrets;
    private JwtService jwtService;
    private SseRunExecutor runExecutor;
    private MockMvc mockMvc;
    private ProductionMetrics metrics;

    @BeforeEach
    void setUp() {
        ProductionProperties properties = properties();
        secrets = new EnvSecretResolver();
        secrets.put(SecretResolver.JWT_HMAC, EnvelopeCrypto.randomKeyBase64());
        jwtService = new JwtService(secrets, Duration.ofHours(1));
        metrics = new ProductionMetrics(new SimpleMeterRegistry());
        ProductionMediaInspector inspector = new ProductionMediaInspector(properties, metrics);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        runExecutor = new SseRunExecutor(new InMemoryRunEventLogDAOImpl(), jsonMapper, properties);
        ProductionChatService chatService = new ProductionChatServiceImpl(
            retrieval, generator, properties, new FakeProductionChatSessionDAOImpl(),
            new InMemorySessionLock(), new ProductionGuardrail(properties, metrics));
        RedisTokenBucket bucket = new RedisTokenBucket(mock(StringRedisTemplate.class), properties);
        AuditService audit = new AuditService(mock(JdbcTemplate.class));
        ProductionChatController chat = new ProductionChatController(
            chatService, mock(ProductionIngestService.class), null, runExecutor,
            null, bucket, null, audit, metrics, jsonMapper, null,
            new ProductionInstanceIdentity("test"), inspector,
            null, null, new ProductionGuardrail(properties, metrics)
        );
        ProductionMediaController media = new ProductionMediaController(
            new ProductionMediaServiceImpl(inspector),
            new ProductionSpeechServiceImpl(inspector, speechClient, properties),
            new ProductionGuardrail(properties, metrics),
            bucket, audit, metrics
        );
        ProductionAuthController auth = new ProductionAuthController(
            new DemoUserService(properties), jwtService, bucket, properties, audit, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(auth, chat, media)
            .setControllerAdvice(new ProductionExceptionHandler())
            .addFilters(new JwtAuthFilter(jwtService, secrets, metrics))
            .build();
    }

    @AfterEach
    void tearDown() {
        runExecutor.shutdown();
    }

    @Test
    void probeWithoutTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/media/probe")
                .file(new MockMultipartFile("file", "tiny.jpg", "image/jpeg", TINY_JPEG)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth_missing_token"));
    }

    @Test
    void probeTextShouldReturnDigest() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/media/probe")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mime").value("text/plain"))
            .andExpect(jsonPath("$.bytes").value(5))
            .andExpect(jsonPath("$.sha256").isString());
    }

    @Test
    void probePdfShouldReturnDigest() throws Exception {
        String token = login();
        byte[] pdf = ProductionDocumentFixtures.pdfWithText("hello pdf");
        mockMvc.perform(multipart("/api/v1/media/probe")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mime").value("application/pdf"))
            .andExpect(jsonPath("$.sha256").isString());
        verify(speechClient, never()).transcribe(any(), any(), any());
    }

    @Test
    void probeExeShouldBeUnsupported() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/media/probe")
                .file(new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[] {'M', 'Z', 0x00}))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_unsupported"));
    }

    @Test
    void probeOversizedShouldBeTooLarge() throws Exception {
        String token = login();
        byte[] huge = new byte[(int) ProductionProperties.Media.DEFAULT_MAX_BYTES + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        mockMvc.perform(multipart("/api/v1/media/probe")
                .file(new MockMultipartFile("file", "big.jpg", "image/jpeg", huge))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_too_large"));
    }

    @Test
    void probeJpegShouldReturnDigest() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/media/probe")
                .file(new MockMultipartFile("file", "tiny.jpg", "image/jpeg", TINY_JPEG))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mime").value("image/jpeg"))
            .andExpect(jsonPath("$.bytes").value(TINY_JPEG.length))
            .andExpect(jsonPath("$.sha256").isString());
        verify(speechClient, never()).transcribe(any(), any(), any());
        assertTrue(metrics.snapshot().get("mediaAccepted").doubleValue() >= 1.0);
    }

    @Test
    void transcribeBadMimeShouldRejectBeforeGateway() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/speech/transcribe")
                .file(new MockMultipartFile("audio", "a.txt", "text/plain", "nope".getBytes()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_unsupported"));
        verify(speechClient, never()).transcribe(any(), any(), any());
    }

    @Test
    void speakEmptyShouldBeBadRequest() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/v1/speech/speak")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
        verify(speechClient, never()).speak(anyString());
    }

    @Test
    void speakDenyWordShouldBeInputDeny() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/v1/speech/speak")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"含有违禁演示词\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("input_deny"));
        verify(speechClient, never()).speak(anyString());
    }

    @Test
    void postStreamWithBadImageShouldRejectBeforeModel() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/chat/stream")
                .file(new MockMultipartFile("image", "a.txt", "text/plain", "x".getBytes()))
                .param("question", "这是什么")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_unsupported"));
        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), anyList(), any());
    }

    @Test
    void postStreamWithTooManyJpegsShouldRejectBeforeModel() throws Exception {
        String token = login();
        var request = multipart("/api/v1/chat/stream")
            .param("question", "这是什么")
            .header("Authorization", "Bearer " + token);
        for (int i = 0; i < 4; i++) {
            request.file(new MockMultipartFile("image", "tiny" + i + ".jpg", "image/jpeg", TINY_JPEG));
        }
        mockMvc.perform(request)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_too_many"));
        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), anyList(), any());
    }

    @Test
    void postStreamWithTwoJpegsShouldSetImageCount() throws Exception {
        when(retrieval.retrieve(any())).thenReturn(
            new ProductionRetrievalService.RetrievalResult(List.of(), List.of(), true, "hybrid"));
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(5);
            onChunk.accept("两张示例图。");
            return null;
        }).when(generator).stream(anyString(), any(), any(), any(), anyList(), any());
        String token = login();
        MvcResult result = mockMvc.perform(multipart("/api/v1/chat/stream")
                .file(new MockMultipartFile("image", "a.jpg", "image/jpeg", TINY_JPEG))
                .file(new MockMultipartFile("image", "b.jpg", "image/jpeg", TINY_JPEG))
                .param("question", "对比这两张图")
                .header("Authorization", "Bearer " + token))
            .andExpect(request().asyncStarted())
            .andReturn();
        String body = awaitBody(result);
        assertTrue(body.contains("\"hasImage\":true"), body);
        assertTrue(body.contains("\"imageCount\":2"), body);
        assertTrue(body.contains("event:done"), body);
    }

    @Test
    void postStreamWithJpegShouldSetHasImage() throws Exception {
        when(retrieval.retrieve(any())).thenReturn(
            new ProductionRetrievalService.RetrievalResult(List.of(), List.of(), true, "hybrid"));
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(6);
            onChunk.accept("图中是示例。");
            return null;
        }).when(generator).stream(anyString(), any(), any(), any(), any(), any(), any());
        String token = login();
        MvcResult result = mockMvc.perform(multipart("/api/v1/chat/stream")
                .file(new MockMultipartFile("image", "tiny.jpg", "image/jpeg", TINY_JPEG))
                .param("question", "描述图片")
                .header("Authorization", "Bearer " + token))
            .andExpect(request().asyncStarted())
            .andReturn();
        String body = awaitBody(result);
        assertTrue(body.contains("\"hasImage\":true"), body);
        assertTrue(body.contains("\"imageCount\":1"), body);
        assertTrue(body.contains("event:done"), body);
    }

    @Test
    void postStreamWithTooManyTxtShouldRejectBeforeModel() throws Exception {
        String token = login();
        var request = multipart("/api/v1/chat/stream")
            .param("question", "总结这些文件")
            .header("Authorization", "Bearer " + token);
        for (int i = 0; i < 3; i++) {
            request.file(new MockMultipartFile(
                "document", "a" + i + ".txt", "text/plain", "hello".getBytes()));
        }
        mockMvc.perform(request)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_too_many"));
        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), anyList(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(ChatAttachments.class), any());
    }

    @Test
    void postStreamWithDenyWordTxtShouldRejectBeforeModel() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/chat/stream")
                .file(new MockMultipartFile(
                    "document", "bad.txt", "text/plain", "含有违禁演示词".getBytes()))
                .param("question", "总结这份文件")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("input_deny"));
        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), anyList(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(ChatAttachments.class), any());
    }

    @Test
    void postStreamWithTxtShouldSetDocumentCount() throws Exception {
        when(retrieval.retrieve(any())).thenReturn(
            new ProductionRetrievalService.RetrievalResult(List.of(), List.of(), true, "hybrid"));
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(5);
            onChunk.accept("文档摘要。");
            return null;
        }).when(generator).stream(anyString(), any(), any(), any(), any(ChatAttachments.class), any());
        String token = login();
        MvcResult result = mockMvc.perform(multipart("/api/v1/chat/stream")
                .file(new MockMultipartFile("document", "note.txt", "text/plain", "hello doc".getBytes()))
                .param("question", "总结这份文件")
                .header("Authorization", "Bearer " + token))
            .andExpect(request().asyncStarted())
            .andReturn();
        String body = awaitBody(result);
        assertTrue(body.contains("\"hasDocument\":true"), body);
        assertTrue(body.contains("\"documentCount\":1"), body);
        assertTrue(body.contains("event:done"), body);
    }

    @Test
    void postAgentStreamWithoutTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/v1/agent/stream")
                .file(new MockMultipartFile("document", "a.txt", "text/plain", "hello".getBytes()))
                .param("question", "总结这份文件"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth_missing_token"));
    }

    @Test
    void postAgentStreamWithTooManyTxtShouldRejectBeforeLoop() throws Exception {
        String token = login();
        var request = multipart("/api/v1/agent/stream")
            .param("question", "总结这些文件")
            .header("Authorization", "Bearer " + token);
        for (int i = 0; i < 3; i++) {
            request.file(new MockMultipartFile(
                "document", "a" + i + ".txt", "text/plain", "hello".getBytes()));
        }
        mockMvc.perform(request)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("media_too_many"));
        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(ChatAttachments.class), any());
    }

    @Test
    void postAgentStreamWithDenyWordTxtShouldRejectBeforeLoop() throws Exception {
        String token = login();
        mockMvc.perform(multipart("/api/v1/agent/stream")
                .file(new MockMultipartFile(
                    "document", "bad.txt", "text/plain", "含有违禁演示词".getBytes()))
                .param("question", "总结这份文件")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("input_deny"));
        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        verify(generator, never()).stream(anyString(), any(), any(), any(), any(ChatAttachments.class), any());
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"demo\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        int start = body.indexOf("\"token\":\"") + 9;
        return body.substring(start, body.indexOf('"', start));
    }

    private static String awaitBody(MvcResult result) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString();
            if (body.contains("event:done") || body.contains("event:error")) {
                return body;
            }
            Thread.sleep(20L);
        }
        return body;
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            new ProductionProperties.Stream("memory", Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(10)),
            new ProductionProperties.Session(true, "memory", 20, 2000, Duration.ofMinutes(1), 3),
            null, null, null, null, null, null
        );
    }
}

/** 与 Inspector 单测共用最小 JPEG，避免 MVC 测试依赖另一个测试类的包可见字段。 */
final class ProductionMediaInspectorTestJpeg {
    static final byte[] BYTES = new byte[] {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    private ProductionMediaInspectorTestJpeg() {}
}
