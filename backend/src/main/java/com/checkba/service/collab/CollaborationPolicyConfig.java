// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * 按 {@code collab.eligibility.policy} 挑一个资格判定实现（{@code firm-or-team | open}）。
 *
 * <p>写错值一律**启动即失败**：静默落回 open 的后果是一台本该按律所限制的案件库
 * 变成谁都能被加进案卷，而日志里一个字都没有。
 */
@Configuration
public class CollaborationPolicyConfig {

    @Bean
    public CollaborationPolicy collaborationPolicy(
            @Value("${collab.eligibility.policy:open}") String policy) {
        String value = policy == null ? "" : policy.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "firm-or-team" -> new SameFirmOrTeamPolicy();
            // 空 = 环境变量给了个空串，与没配同义（application.yml 的默认就是 open）
            case "open", "" -> new OpenPolicy();
            default -> throw new IllegalStateException(
                    "collab.eligibility.policy 取值无效: \"" + policy + "\"；只接受 firm-or-team 或 open");
        };
    }
}
