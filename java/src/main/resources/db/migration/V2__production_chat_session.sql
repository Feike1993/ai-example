-- 工业级链路的会话表。
--
-- 为什么不复用 chat_session_message：生产侧需要 tenant_id / turn_id / run_id
-- 三列，样例表没有。共用一张表就得让教学代码跟着改，两边的演进节奏会互相绑死。
--
-- Flyway 只管应用自己的表。vector_store 归 Spring AI 的
-- spring.ai.vectorstore.pgvector.initialize-schema，全文索引归
-- RagKeywordRetriever.ensureFullTextIndex()——同一个对象只能有一个 owner，
-- 否则迁移与运行期 DDL 会互相打架（校验和漂移、并发建索引冲突）。

CREATE TABLE prod_chat_session (
    session_id VARCHAR(64) PRIMARY KEY,
    -- 第三阶段做多租户隔离时才真正参与查询条件，这里先建好占位，避免届时对存量数据做 backfill
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE prod_chat_message (
    session_id VARCHAR(64) NOT NULL REFERENCES prod_chat_session (session_id) ON DELETE CASCADE,
    seq        INTEGER     NOT NULL,
    -- 一轮对话的 user 与 assistant 共享同一个 turn_id
    turn_id    UUID        NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    -- 产生这条消息的 SSE run，便于事后把回答和当时的事件流对上
    run_id     VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- 复合主键即并发兜底：两个事务算出同一个 seq 时，后提交的那个必然冲突，
    -- 由应用层重试重新取号。正确性不依赖分布式锁。
    PRIMARY KEY (session_id, seq)
);

-- 幂等键：seq 冲突重试会重放整条 INSERT，靠它保证同一 turn 的同一角色只落一次
CREATE UNIQUE INDEX uk_prod_chat_message_turn_role ON prod_chat_message (turn_id, role);
