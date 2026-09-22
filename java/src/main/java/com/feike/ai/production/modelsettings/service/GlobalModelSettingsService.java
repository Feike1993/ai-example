package com.feike.ai.production.modelsettings.service;

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
    private final SecretResolver secrets;

    public GlobalModelSettingsService(JdbcTemplate jdbc, SecretResolver secrets) {
        this.jdbc = jdbc; this.secrets = secrets;
    }

    public ModelSettingsVO list() {
        List<GlobalProviderVO> providers = jdbc.query("SELECT provider_id,label,base_url,model,models,capabilities,temperature,enable_thinking,bypass_proxy FROM prod_model_provider ORDER BY provider_id",
            (rs, n) -> new GlobalProviderVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                split(rs.getString(5)), split(rs.getString(6)), nullableDouble(rs, 7), nullableBoolean(rs, 8),
                rs.getBoolean(9), secrets.contains(SecretResolver.llmKey(rs.getString(1)))));
        List<ModelRouteVO> routes = jdbc.query("SELECT capability,provider_id,model,voice FROM prod_model_route ORDER BY capability",
            (rs, n) -> new ModelRouteVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        return new ModelSettingsVO(providers, routes);
    }

    public void saveProvider(String id, ProviderUpsertRequest request) {
        validateId(id); validateUrl(request.baseUrl());
        List<String> capabilities = normalizeCapabilities(request.capabilities());
        String label = required(request.label(), "Provider 名称");
        List<String> models = normalizeModels(request.models(), request.model());
        validateTemperature(request.temperature());
        List<String> routedModels = jdbc.queryForList("SELECT model FROM prod_model_route WHERE provider_id=?", String.class, id);
        List<String> removedRoutedModels = routedModels.stream().filter(model -> !models.contains(model)).distinct().toList();
        if (!removedRoutedModels.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "模型正在被能力路由使用，不能移除: " + String.join(", ", removedRoutedModels));
        }
        jdbc.update("""
            INSERT INTO prod_model_provider(provider_id,label,base_url,model,models,capabilities,temperature,enable_thinking,bypass_proxy,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,NOW()) ON CONFLICT (provider_id) DO UPDATE SET label=EXCLUDED.label,
              base_url=EXCLUDED.base_url, model=EXCLUDED.model, models=EXCLUDED.models,
              capabilities=EXCLUDED.capabilities, temperature=EXCLUDED.temperature,
              enable_thinking=EXCLUDED.enable_thinking, bypass_proxy=EXCLUDED.bypass_proxy, updated_at=NOW()
            """, id, label, request.baseUrl().trim(), models.getFirst(), String.join(",", models),
            String.join(",", capabilities), request.temperature(), request.enableThinking(),
            Boolean.TRUE.equals(request.bypassProxy()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            secrets.put(SecretResolver.llmKey(id), request.apiKey().trim());
        }
    }

    public void saveRoute(String capability, RouteUpsertRequest request) {
        if (!CAPABILITIES.contains(capability) || "tools".equals(capability)) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "不支持的路由能力");
        String provider = required(request.providerId(), "Provider");
        List<ProviderScope> providers = jdbc.query("SELECT models,capabilities FROM prod_model_provider WHERE provider_id=?",
            (rs, n) -> new ProviderScope(split(rs.getString(1)), split(rs.getString(2))), provider);
        if (providers.isEmpty()) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "路由 Provider 不存在");
        ProviderScope selected = providers.getFirst();
        if (!selected.capabilities().contains(capability)) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "Provider 不支持该路由能力");
        String model = required(request.model(), "模型名");
        if (!selected.models().contains(model)) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "路由模型不在 Provider 的可用模型列表中");
        String voice = blankToNull(request.voice());
        if ("tts".equals(capability) && voice == null) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "TTS 音色不能为空");
        jdbc.update("""
            INSERT INTO prod_model_route(capability,provider_id,model,voice,updated_at) VALUES (?,?,?,?,NOW())
            ON CONFLICT (capability) DO UPDATE SET provider_id=EXCLUDED.provider_id,model=EXCLUDED.model,voice=EXCLUDED.voice,updated_at=NOW()
            """, capability, provider, model, voice);
    }

    /** 运行时读取 Provider；不返回密钥，密钥仍只由 SecretResolver 解密。 */
    public GlobalProviderVO provider(String id) {
        List<GlobalProviderVO> rows = jdbc.query("SELECT provider_id,label,base_url,model,models,capabilities,temperature,enable_thinking,bypass_proxy FROM prod_model_provider WHERE provider_id=?",
            (rs, n) -> new GlobalProviderVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                split(rs.getString(5)), split(rs.getString(6)), nullableDouble(rs, 7), nullableBoolean(rs, 8),
                rs.getBoolean(9), secrets.contains(SecretResolver.llmKey(rs.getString(1)))), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 当前能力路由；保存后下一次运行时调用即可读取新值。 */
    public ModelRouteVO route(String capability) {
        List<ModelRouteVO> rows = jdbc.query("SELECT capability,provider_id,model,voice FROM prod_model_route WHERE capability=?",
            (rs, n) -> new ModelRouteVO(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), capability);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static List<String> normalizeCapabilities(List<String> input) {
        List<String> values = input == null ? List.of() : input.stream().filter(Objects::nonNull).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(CAPABILITIES::contains).distinct().toList();
        if (values.isEmpty()) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "至少选择一项能力");
        return values;
    }
    private static List<String> normalizeModels(List<String> input, String legacyModel) {
        List<String> source = input == null || input.isEmpty()
            ? (legacyModel == null ? List.of() : List.of(legacyModel))
            : input;
        List<String> values = source.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        if (values.isEmpty()) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "至少添加一个模型");
        if (values.stream().anyMatch(value -> value.contains(","))) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "模型名不能包含英文逗号");
        return values;
    }
    private static List<String> split(String raw) { return raw == null || raw.isBlank() ? List.of() : Arrays.stream(raw.split(",")).toList(); }
    private static Double nullableDouble(java.sql.ResultSet rs, int index) throws java.sql.SQLException { double value = rs.getDouble(index); return rs.wasNull() ? null : value; }
    private static Boolean nullableBoolean(java.sql.ResultSet rs, int index) throws java.sql.SQLException { boolean value = rs.getBoolean(index); return rs.wasNull() ? null : value; }
    private static void validateTemperature(Double value) { if (value != null && (value < 0 || value > 2)) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "温度必须在 0 到 2 之间"); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, field + "不能为空"); return value.trim(); }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static void validateId(String id) { if (id == null || !id.matches("[a-z][a-z0-9-]{0,47}")) throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "Provider id 只能使用小写字母、数字和连字符"); }
    private static void validateUrl(String value) { try { URI uri = URI.create(required(value, "基础地址")); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException(); } catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "基础地址必须是无用户名密码的 HTTP(S) 地址"); } }
    private record ProviderScope(List<String> models, List<String> capabilities) {}
}
