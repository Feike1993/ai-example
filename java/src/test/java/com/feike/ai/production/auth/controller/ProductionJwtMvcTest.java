package com.feike.ai.production.auth.controller;

import com.feike.ai.production.auth.manager.DemoUserService;
import com.feike.ai.production.auth.manager.JwtService;
import com.feike.ai.production.chat.service.impl.ProductionChatServiceImpl;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.config.ProductionInstanceIdentity;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.chat.controller.ProductionChatController;
import com.feike.ai.production.chat.service.ProductionChatService;
import com.feike.ai.production.lock.manager.InMemorySessionLock;
import com.feike.ai.production.observability.controller.ProductionOpsController;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.model.ProductionSource;
import com.feike.ai.production.rag.generate.service.ProductionAnswerGenerator;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import com.feike.ai.production.secret.manager.EnvSecretResolver;
import com.feike.ai.production.secret.manager.EnvelopeCrypto;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.session.dao.impl.FakeProductionChatSessionDAOImpl;
import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.service.SseRunExecutor;
import com.feike.ai.production.web.ProductionExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT 过滤器武装后的工业级接口：未登录 401、跨租户 404、ingest 非管理员 403、缺密钥 503。
 */
@DisplayName("Production JWT MVC")
class ProductionJwtMvcTest {

    private final ProductionRetrievalService retrieval = mock(ProductionRetrievalService.class);
    private final ProductionAnswerGenerator generator = mock(ProductionAnswerGenerator.class);
    private final ProductionIngestService ingestService = mock(ProductionIngestService.class);

    private EnvSecretResolver secrets;
    private JwtService jwtService;
    private SseRunExecutor runExecutor;
    private MockMvc mockMvc;
    private FakeProductionChatSessionDAOImpl sessionStore;
    private InMemoryRunEventLogDAOImpl eventLog;

    @BeforeEach
    void setUp() {
        ProductionProperties properties = properties();
        secrets = new EnvSecretResolver();
        secrets.put(SecretResolver.JWT_HMAC, EnvelopeCrypto.randomKeyBase64());
        jwtService = new JwtService(secrets, Duration.ofHours(1));
        sessionStore = new FakeProductionChatSessionDAOImpl();
        eventLog = new InMemoryRunEventLogDAOImpl();
        JsonMapper jsonMapper = JsonMapper.builder().build();
        runExecutor = new SseRunExecutor(eventLog, jsonMapper, properties);
        ProductionChatService chatService = new ProductionChatServiceImpl(
            retrieval, generator, properties, sessionStore, new InMemorySessionLock(), null);
        RedisTokenBucket bucket = new RedisTokenBucket(mock(StringRedisTemplate.class), properties);
        AuditService audit = new AuditService(mock(JdbcTemplate.class));
        ProductionMetrics metrics = new ProductionMetrics(new SimpleMeterRegistry());
        DemoUserService users = new DemoUserService(properties);
        ProductionAuthController auth = new ProductionAuthController(
            users, jwtService, bucket, properties, audit, metrics);
        ProductionChatController chat = new ProductionChatController(
            chatService, ingestService, null, runExecutor,
            null, bucket, null, audit, metrics, jsonMapper, null,
            new ProductionInstanceIdentity("test")
        );
        @SuppressWarnings("unchecked")
        ObjectProvider<RunEventLogDAO> logs = mock(ObjectProvider.class);
        when(logs.getIfAvailable()).thenReturn(eventLog);
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.tracing.Tracer> tracer = mock(ObjectProvider.class);
        when(tracer.getIfAvailable()).thenReturn(null);
        ProductionOpsController ops = new ProductionOpsController(
            audit, metrics, tracer, null, jsonMapper, "", logs);
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, secrets, metrics);
    mockMvc = MockMvcBuilders.standaloneSetup(auth, chat, ops)
        .setControllerAdvice(new ProductionExceptionHandler())
        .addFilters(filter)
        .build();
    }

    @AfterEach
    void tearDown() {
        runExecutor.shutdown();
    }

    @Test
    void missingTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth_missing_token"))
            .andExpect(jsonPath("$.message").value("缺少 Authorization: Bearer 令牌"));
    }

    @Test
    void loginFailureShouldReturnBusinessCopy() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth_login_failed"))
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void loginAndMeShouldEchoPrincipal() throws Exception {
        String token = login("alice");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.tenant").value("tenant-a"));
    }

    @Test
    void ingestAsUserShouldBeForbidden() throws Exception {
        String token = login("alice");
        mockMvc.perform(post("/api/v1/rag/ingest").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ingest_forbidden"))
            .andExpect(jsonPath("$.message").value("重建索引需要 ADMIN 角色"));
    }

    @Test
    void ingestAsAdminShouldSucceed() throws Exception {
        when(ingestService.ingest()).thenReturn(
            new ProductionIngestService.IngestResult("c", 1, List.of("a.md")));
        String token = login("admin");
        mockMvc.perform(post("/api/v1/rag/ingest").header("Authorization", "Bearer " + token))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    void lockProbeShouldEchoSessionWithoutLlm() throws Exception {
        String token = login("alice");
        mockMvc.perform(post("/api/v1/sessions/s-probe/lock-probe")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdMs\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("s-probe"))
            .andExpect(jsonPath("$.instanceId").value("test"))
            .andExpect(jsonPath("$.heldMs").value(1));
    }

    @Test
    void aliceShouldNotSeeRebuildIndexTool() throws Exception {
        String token = login("alice");
        mockMvc.perform(get("/api/v1/agent/tools").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools").isArray())
            .andExpect(jsonPath("$.tools").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem("rebuild_index"))));
    }

    @Test
    void adminShouldSeeRebuildIndexTool() throws Exception {
        String token = login("admin");
        mockMvc.perform(get("/api/v1/agent/tools").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools").value(org.hamcrest.Matchers.hasItem("rebuild_index")));
    }

    @Test
    void aliceToolProbeShouldDenyRebuildIndex() throws Exception {
        String token = login("alice");
        mockMvc.perform(post("/api/v1/agent/tool-probe")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"rebuild_index\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tool").value("rebuild_index"))
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(jsonPath("$.denied").value(true));
    }

    @Test
    void adminToolProbeShouldAllowRebuildIndexWithoutIngest() throws Exception {
        String token = login("admin");
        mockMvc.perform(post("/api/v1/agent/tool-probe")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"rebuild_index\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.denied").value(false));
    }

    @Test
    void missingRunTimelineShouldLookAbsent() throws Exception {
        String token = login("alice");
        mockMvc.perform(get("/api/v1/ops/runs/missing-run").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("run_not_found"));
    }

    @Test
    void crossTenantSessionShouldLookMissing() throws Exception {
        stubChat();
        sessionStore.appendTurn("tenant-a", "s-alice", UUID.randomUUID(), "run", "问", "答");
        String bob = login("bob");
        mockMvc.perform(get("/api/v1/sessions/s-alice").header("Authorization", "Bearer " + bob))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("session_not_found"))
            .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void unavailableSecretsShouldServiceUnavailable() throws Exception {
        SecretResolver down = mock(SecretResolver.class);
        when(down.available()).thenReturn(false);
        ProductionProperties properties = properties();
        MockMvc locked = MockMvcBuilders.standaloneSetup(
                new ProductionAuthController(
                    new DemoUserService(properties),
                    jwtService,
                    new RedisTokenBucket(mock(StringRedisTemplate.class), properties),
                    properties,
                    new AuditService(mock(JdbcTemplate.class)),
                    new ProductionMetrics(new SimpleMeterRegistry())
                ))
            .addFilters(new JwtAuthFilter(jwtService, down))
            .setControllerAdvice(new ProductionExceptionHandler())
            .build();
        locked.perform(get("/api/v1/me"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("secret_unavailable"));
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"demo\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        int start = body.indexOf("\"token\":\"") + 9;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private void stubChat() {
        Document doc = Document.builder()
            .id("doc-1")
            .text("RAG")
            .metadata(Map.of("source", "03-rag.md"))
            .build();
        when(retrieval.retrieve(any(com.feike.ai.production.rag.retrieve.model.ProductionRetrieveQuery.class))).thenReturn(
            new ProductionRetrievalService.RetrievalResult(
                List.of(doc),
                List.of(new ProductionSource("doc-1", "03-rag.md", "RAG", null)),
                false,
                "hybrid"
            ));
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(4);
            onChunk.accept("答");
            return null;
        }).when(generator).stream(anyString(), any(), any(), any(), any());
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            new ProductionProperties.Stream("memory", Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(10)),
            new ProductionProperties.Session(true, "memory", 20, 2000, Duration.ofMinutes(1), 3),
            null, null, null, null, null
        );
    }
}
