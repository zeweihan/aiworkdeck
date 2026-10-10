-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- 试用计量 v0.1.1 · trial_balance（MySQL：application-prod.yml 档）
-- 仓库约定：表结构由 spring.jpa.hibernate.ddl-auto=update 自动建；本文件是与实体
-- com.checkba.model.entity.TrialBalance 对拍的**参考 DDL**，不被代码自动执行。
-- Instant 列按 UTC 存（DATETIME(6)），与 Hibernate 6 默认映射一致。
-- 注意：本表只是账户站试用余额的本地缓存，不是计费权威。

CREATE TABLE IF NOT EXISTS `trial_balance` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`          BIGINT       NOT NULL,
    `account_fingerprint` VARCHAR(12),
    `account_snapshot` TEXT,
    `trial_started_at` DATETIME(6)  NOT NULL,
    `trial_ends_at`    DATETIME(6)  NOT NULL,
    `calls_quota`      INT          NOT NULL,
    `calls_used`       INT          NOT NULL DEFAULT 0,
    `status`           VARCHAR(32)  NOT NULL,
    `policy`           VARCHAR(32)  NOT NULL,
    `region`           VARCHAR(8)   NULL,
    `created_at`       DATETIME(6)  NOT NULL,
    `updated_at`       DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trial_balance_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
