package com.feike.ai.production.modelsettings.service;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.modelsettings.model.*;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.util.*;

/** 全局 Provider/能力路由的持久化服务；密钥永远委托 SecretResolver。 */
public class GlobalModelSettingsService {
    private static final Set<String> CAPABILITIES = Set.of("chat", "tools", "vision", "embedding", "asr", "tts");
    private final JdbcTemplate jdbc;
    private final AiProperties ai;
    private final ProductionProperties production;
    private final SecretResolver secrets;

    public GlobalModelSettingsService(JdbcTemplate jdbc, AiProperties ai, ProductionProperties production, SecretResolver secrets) {
        this.jdbc = jdbc; this.ai = ai; this.production = production; this.secrets = secrets;
    }

    public ModelSettingsVO list() {
        seedIfEmpty();
        List<GlobalProviderVO> providers = jdbc.query("SELECT provider_id,label,base_url,model,capabilities FROM prod_model_provider ORDER BY provider_id",
            (rs, n) -> new GlobalProviderVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                split(rs.getString(5)), secrets.contains(SecretResolver.llmKey(rs.getString(1)))));
        List<ModelRouteVO> routes = jdbc.query("SELECT capability,provider_id,model,voice FROM prod_model_route ORDER BY capability",
            (rs, n) -> new ModelRouteVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        return new ModelSettingsVO(providers, routes);
    }

    public void saveProvider(String id, ProviderUpsertRequest request) {
        validateId(id); validateUrl(request.baseUrl());
        List<String> capabilities = normalizeCapabilities(request.capabilities());
        String label = required(request.label(), "Provider 名称");
        String model = required(request.model(), "模型名");
        jdbc.update("""
            INSERT INTO prod_model_provider(provider_id,label,base_url,model,capabilities,updated_at)
            VALUES (?,?,?,?,?,NOW()) ON CONFLICT (provider_id) DO UPDATE SET label=EXCLUDED.label,
              base_url=EXCLUDED.base_url, model=EXCLUDED.model, capabilities=EXCLUDED.capabilities, updated_at=NOW()
            """, id, label, request.baseUrl().trim(), model, String.join(",", capabilities));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            secrets.put(SecretResolver.llmKey(id), request.apiKey().trim());
        }
    }

    public void saveRoute(String capability, RouteUpsertRequest request) {
        if (!CAPABILITIES.contains(capability) || "tools".equals(capability)) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "不支持的路由能力");
        String provider = required(request.providerId(), "Provider");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM prod_model_provider WHERE provider_id=?", Integer.class, provider);
        if (count == null || count == 0) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "路由 Provider 不存在");
        jdbc.update("""
            INSERT INTO prod_model_route(capability,provider_id,model,voice,updated_at) VALUES (?,?,?,?,NOW())
            ON CONFLICT (capability) DO UPDATE SET provider_id=EXCLUDED.provider_id,model=EXCLUDED.model,voice=EXCLUDED.voice,updated_at=NOW()
            """, capability, provider, required(request.model(), "模型名"), blankToNull(request.voice()));
    }

    /** 运行时读取 Provider；不返回密钥，密钥仍只由 SecretResolver 解密。 */
    public GlobalProviderVO provider(String id) {
        seedIfEmpty();
        List<GlobalProviderVO> rows = jdbc.query("SELECT provider_id,label,base_url,model,capabilities FROM prod_model_provider WHERE provider_id=?",
            (rs, n) -> new GlobalProviderVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), split(rs.getString(5)), secrets.contains(SecretResolver.llmKey(rs.getString(1)))), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 当前能力路由；保存后下一次运行时调用即可读取新值。 */
    public ModelRouteVO route(String capability) {
        seedIfEmpty();
        List<ModelRouteVO> rows = jdbc.query("SELECT capability,provider_id,model,voice FROM prod_model_route WHERE capability=?",
            (rs, n) -> new ModelRouteVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), capability);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM prod_model_provider", Integer.class);
        if (count != null && count > 0) return;
        for (var entry : ai.providers().entrySet()) {
            var p = entry.getValue();
            jdbc.update("INSERT INTO prod_model_provider(provider_id,label,base_url,model,capabilities) VALUES (?,?,?,?,?)",
                entry.getKey(), p.label() == null ? entry.getKey() : p.label(), p.baseUrl(), p.model(), String.join(",", p.capabilities()));
        }
        var media = production.media();
        saveSeedRoute("chat", ai.defaultProvider(), ai.providers().get(ai.defaultProvider()).model(), null);
        saveSeedRoute("vision", media.visionProvider(), media.visionModel(), null);
        saveSeedRoute("embedding", ai.embeddingProvider(), ai.embedding().model(), null);
        saveSeedRoute("asr", media.asrProvider(), media.asrModel(), null);
        saveSeedRoute("tts", media.ttsProvider(), media.ttsModel(), media.ttsVoice());
    }
    private void saveSeedRoute(String capability, String provider, String model, String voice) {
        jdbc.update("INSERT INTO prod_model_route(capability,provider_id,model,voice) VALUES (?,?,?,?) ON CONFLICT DO NOTHING", capability, provider, model, voice);
    }
    private static List<String> normalizeCapabilities(List<String> input) {
        List<String> values = input == null ? List.of() : input.stream().filter(Objects::nonNull).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(CAPABILITIES::contains).distinct().toList();
        if (values.isEmpty()) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "至少选择一项能力");
        return values;
    }
    private static List<String> split(String raw) { return raw == null || raw.isBlank() ? List.of() : Arrays.stream(raw.split(",")).toList(); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, field + "不能为空"); return value.trim(); }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static void validateId(String id) { if (id == null || !id.matches("[a-z][a-z0-9-]{0,47}")) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "Provider id 只能使用小写字母、数字和连字符"); }
    private static void validateUrl(String value) { try { URI uri = URI.create(required(value, "基础地址")); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException(); } catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "基础地址必须是无用户名密码的 HTTP(S) 地址"); } }
}
