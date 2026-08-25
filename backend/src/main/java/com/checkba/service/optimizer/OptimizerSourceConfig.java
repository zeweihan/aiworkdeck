package com.checkba.service.optimizer;

import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 选优化者读哪儿：{@code optimizer.source=local}（默认，读本进程库）或 {@code remote}（读云端收件箱）。
 *
 * <p>remote 缺 base-url 或 token 时**直接拒绝启动**，不静默退回 local——
 * 退回的表现是「优化者天天跑、天天零条」，比起不起来更难发现。
 */
@Slf4j
@Configuration
public class OptimizerSourceConfig {

    @Bean
    public OptimizerFeedbackSource optimizerFeedbackSource(OptimizerProperties props,
                                                           UserFeedbackRepository repository,
                                                           FeedbackService feedbackService) {
        String mode = props.getSource() == null ? "local" : props.getSource().trim().toLowerCase();
        if (!"remote".equals(mode)) {
            return new LocalFeedbackSource(repository, feedbackService);
        }
        String base = props.getRemote().getBaseUrl();
        String token = props.getRemote().getToken();
        if (base == null || base.isBlank() || token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "optimizer.source=remote 但缺 optimizer.remote.base-url 或 optimizer.remote.token");
        }
        if (!base.startsWith("https://") && !base.startsWith("http://127.0.0.1")
                && !base.startsWith("http://localhost")) {
            // 取件与回执上跑的是 token 与用户反馈原文，明文出公网不行
            throw new IllegalStateException("optimizer.remote.base-url 必须是 https（本机回环除外）");
        }
        log.info("[optimizer] 反馈来源 = 云端收件箱 {}", base);
        return new RemoteFeedbackSource(base, token, props.getRemote().getConsoleUrl());
    }
}
