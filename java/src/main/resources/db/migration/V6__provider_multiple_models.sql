-- 一个 Provider 可登记多个模型；model 保留为首选模型，兼容既有运行时与旧客户端。
ALTER TABLE prod_model_provider ADD COLUMN models VARCHAR(2048);

-- 旧数据里视觉、向量、语音路由可能使用不同于 Provider 默认值的模型，迁移时一并保留。
UPDATE prod_model_provider provider
SET models = CONCAT_WS(',', provider.model, (
    SELECT STRING_AGG(DISTINCT route.model, ',')
    FROM prod_model_route route
    WHERE route.provider_id = provider.provider_id
      AND route.model <> provider.model
));

ALTER TABLE prod_model_provider ALTER COLUMN models SET NOT NULL;

COMMENT ON COLUMN prod_model_provider.models IS 'Provider 可用模型列表，多个模型使用英文逗号分隔';
