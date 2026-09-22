package com.feike.ai.production.modelsettings.service;

import com.feike.ai.production.modelsettings.model.ProviderUpsertRequest;
import com.feike.ai.production.modelsettings.model.RouteUpsertRequest;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalModelSettingsServiceTest {

    @Test
    void providerShouldPersistDistinctModelsAndKeepFirstAsDefault() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("dashscope"))).thenReturn(List.of());
        GlobalModelSettingsService service = service(jdbc);

        service.saveProvider("dashscope", new ProviderUpsertRequest(
            "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", null,
            List.of("qwen-max", "qwen-vl-max", "qwen-max"), List.of("chat", "vision"),
            0.3, false, true, null
        ));

        verify(jdbc).update(anyString(), eq("dashscope"), eq("阿里云百炼"),
            eq("https://dashscope.aliyuncs.com/compatible-mode/v1"), eq("qwen-max"),
            eq("qwen-max,qwen-vl-max"), eq("chat,vision"), eq(0.3), eq(false), eq(true));
    }

    @Test
    void routeShouldRejectModelOutsideProviderModelList() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubProviderScope(jdbc, "dashscope", "qwen-max,qwen-vl-max", "chat,vision");
        GlobalModelSettingsService service = service(jdbc);

        assertThrows(BusinessException.class,
            () -> service.saveRoute("chat", new RouteUpsertRequest("dashscope", "other-model", null)));
    }

    private static GlobalModelSettingsService service(JdbcTemplate jdbc) {
        return new GlobalModelSettingsService(jdbc, mock(SecretResolver.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubProviderScope(JdbcTemplate jdbc, String provider, String models, String capabilities) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(models);
        when(resultSet.getString(2)).thenReturn(capabilities);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(provider))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });
    }
}
