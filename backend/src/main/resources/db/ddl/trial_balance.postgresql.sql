-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- 试用计量 v0.1.1 · trial_balance（PostgreSQL：application.yml / -case / -cloud 档）
-- 仓库约定：表结构由 spring.jpa.hibernate.ddl-auto=update 自动建（见 deploy/case/README.md），
-- 本文件是与实体 com.checkba.model.entity.TrialBalance 对拍的**参考 DDL**，供运维手工预建/审计；
-- 不被任何代码自动执行。改实体必须同步改本文件（TrialBalanceDdlTest 会对拍列名）。
-- 注意：本表只是账户站试用余额的本地缓存，不是计费权威。

CREATE TABLE IF NOT EXISTS trial_balance (
    id                 BIGSERIAL    PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    account_fingerprint VARCHAR(12),
    account_snapshot TEXT,
    trial_started_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    trial_ends_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    calls_quota        INTEGER      NOT NULL,
    calls_used         INTEGER      NOT NULL DEFAULT 0,
    status             VARCHAR(32)  NOT NULL,
    policy             VARCHAR(32)  NOT NULL,
    region             VARCHAR(8),
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_trial_balance_user UNIQUE (user_id)
);
