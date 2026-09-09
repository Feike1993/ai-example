-- 第三阶段：信封加密密钥表、审计表、会话按租户查询索引。
--
-- prod_secret 只存密文。能解开它的 KEK 来自环境变量 PRODUCTION_KEK，
-- 禁止把 KEK 写入本表——密文和钥匙放一起等于没加密。

CREATE TABLE prod_secret (
    name       VARCHAR(128) PRIMARY KEY,
    ciphertext BYTEA        NOT NULL,
    nonce      BYTEA        NOT NULL,
    kek_id     VARCHAR(32)  NOT NULL DEFAULT 'v1',
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE prod_audit_log (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    principal      VARCHAR(128) NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    path           VARCHAR(256) NOT NULL,
    status         INTEGER      NOT NULL,
    run_id         VARCHAR(64),
    question_sha256 VARCHAR(64),
    duration_ms    INTEGER,
    ip             VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prod_audit_tenant_created ON prod_audit_log (tenant_id, created_at DESC);

-- 按租户查会话；session_id 仍是全局主键，跨租户碰撞走 404 而不是改 PK
CREATE INDEX idx_prod_chat_session_tenant ON prod_chat_session (tenant_id, session_id);
