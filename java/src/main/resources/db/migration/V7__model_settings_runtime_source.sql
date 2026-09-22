-- 工业级模型运行参数统一落到模型与服务设置；YAML 只保留教学样例配置。
ALTER TABLE prod_model_provider ADD COLUMN temperature DOUBLE PRECISION;
ALTER TABLE prod_model_provider ADD COLUMN enable_thinking BOOLEAN;
ALTER TABLE prod_model_provider ADD COLUMN bypass_proxy BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN prod_model_provider.temperature IS '聊天采样温度，为空时使用上游服务默认值';
COMMENT ON COLUMN prod_model_provider.enable_thinking IS '是否启用模型思考模式，为空时不发送覆盖参数';
COMMENT ON COLUMN prod_model_provider.bypass_proxy IS '是否绕过 JVM HTTP 代理直连模型网关';

-- 仅负责新库的可编辑初始值；运行时始终读取这些表，管理员保存后立即生效。
INSERT INTO prod_model_provider(provider_id,label,base_url,model,models,capabilities,temperature,enable_thinking,bypass_proxy)
VALUES
  ('deepseek','DeepSeek','https://api.deepseek.com','deepseek-v4-flash','deepseek-v4-flash','chat,tools',0.2,NULL,FALSE),
  ('dashscope','阿里云百炼','https://dashscope.aliyuncs.com/compatible-mode','qwen3.8-max','qwen3.8-max,qwen-vl-plus,text-embedding-v3,qwen3-asr-flash,qwen3-tts-flash','chat,vision,embedding,asr,tts',0.2,NULL,FALSE),
  ('kimi','Kimi','https://api.moonshot.cn/v1','kimi-latest','kimi-latest','chat,vision',1.0,NULL,FALSE),
  ('glm','智谱 GLM','https://open.bigmodel.cn/api/coding/paas/v4','glm-5','glm-5','chat,tools',0.2,NULL,FALSE),
  ('qwen35','qwen3.5','https://vllm.naradapower.com','qwennaradav1','qwennaradav1','chat,tools',0.2,FALSE,TRUE)
ON CONFLICT (provider_id) DO NOTHING;

UPDATE prod_model_provider SET temperature=0.2 WHERE provider_id IN ('deepseek','dashscope','glm','qwen35') AND temperature IS NULL;
UPDATE prod_model_provider SET temperature=1.0 WHERE provider_id='kimi' AND temperature IS NULL;
UPDATE prod_model_provider SET enable_thinking=FALSE, bypass_proxy=TRUE WHERE provider_id='qwen35';

INSERT INTO prod_model_route(capability,provider_id,model,voice)
VALUES
  ('chat','deepseek','deepseek-v4-flash',NULL),
  ('vision','dashscope','qwen-vl-plus',NULL),
  ('embedding','dashscope','text-embedding-v3',NULL),
  ('asr','dashscope','qwen3-asr-flash',NULL),
  ('tts','dashscope','qwen3-tts-flash','Cherry')
ON CONFLICT (capability) DO NOTHING;
