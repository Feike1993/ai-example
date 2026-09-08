-- 教学样例的会话表，原先由 JdbcChatSessionStore 的 @PostConstruct 建。
--
-- 为什么保留 IF NOT EXISTS：这条迁移是「把已经存在的东西纳入版本管理」，
-- 存量库里这张表早就有了，配合 baseline-on-migrate 必须能幂等通过。
-- 从 V2 开始的新表不再需要这样写。
--
-- 表结构与原 DDL 逐字一致，本次纳管不改变样例行为。
CREATE TABLE IF NOT EXISTS chat_session_message (
    session_id VARCHAR(128) NOT NULL,
    seq        INTEGER      NOT NULL,
    role       VARCHAR(32)  NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, seq)
);
