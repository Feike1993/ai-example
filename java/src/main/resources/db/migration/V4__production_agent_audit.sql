-- 第八阶段：审计补工具名与是否被策略拒绝。问题原文和工具参数仍不落库。

ALTER TABLE prod_audit_log
    ADD COLUMN tool_name VARCHAR(64),
    ADD COLUMN denied BOOLEAN;
