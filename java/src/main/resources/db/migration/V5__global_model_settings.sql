-- 全局模型与服务设置：元数据与密钥分离，API Key 只进 prod_secret 的 AES-GCM 密文列。
CREATE TABLE prod_model_provider (
    provider_id  VARCHAR(48) PRIMARY KEY,
    label        VARCHAR(96) NOT NULL,
    base_url     VARCHAR(512) NOT NULL,
    model        VARCHAR(192) NOT NULL,
    capabilities VARCHAR(256) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN prod_model_provider.provider_id IS '模型提供者唯一标识';
COMMENT ON COLUMN prod_model_provider.label IS '模型提供者显示名称';
COMMENT ON COLUMN prod_model_provider.base_url IS '模型服务 API 基础地址';
COMMENT ON COLUMN prod_model_provider.model IS '模型提供者的默认模型名称';
COMMENT ON COLUMN prod_model_provider.capabilities IS '支持的能力列表，多个能力使用英文逗号分隔';
COMMENT ON COLUMN prod_model_provider.updated_at IS '模型提供者配置最后更新时间';

CREATE TABLE prod_model_route (
    capability   VARCHAR(24) PRIMARY KEY,
    provider_id  VARCHAR(48) NOT NULL REFERENCES prod_model_provider(provider_id),
    model        VARCHAR(192) NOT NULL,
    voice        VARCHAR(96),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN prod_model_route.capability IS '路由对应的模型能力标识';
COMMENT ON COLUMN prod_model_route.provider_id IS '该能力路由使用的模型提供者标识';
COMMENT ON COLUMN prod_model_route.model IS '该能力路由使用的模型名称';
COMMENT ON COLUMN prod_model_route.voice IS '语音能力使用的音色名称，非语音能力为空';
COMMENT ON COLUMN prod_model_route.updated_at IS '模型路由配置最后更新时间';
