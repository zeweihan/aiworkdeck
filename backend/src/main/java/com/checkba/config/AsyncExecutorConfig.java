package com.checkba.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 显式异步线程池配置（2026-08 harness 加固 F-08）。
 *
 * 背景：此前全仓库没有任何 Executor 配置，@Async("taskExecutor") 落到 Spring Boot
 * 默认池——核心 8 / 队列无界。无界队列意味着 max-size 永不生效、拒绝策略永不触发：
 * 编排循环、记忆管线（同步阻塞 LLM 调用，一次可占几十秒）、云同步三方共用 8 个线程，
 * 8 个会话同时收尾即打满，之后的新消息在队列里无限期排队且用户毫无提示
 * （表现为"点了发送、HTTP 200、SSE 上什么都不来"）。
 *
 * 现拆两个池：
 * - taskExecutor：编排循环 + 云同步等交互路径，核心 16 / 最大 32 / 有界队列 200。
 *   队列打满走默认 AbortPolicy——异常会传回调用方而不是静默排队（单用户桌面场景
 *   基本不可能触顶，触顶说明有泄漏，宁可炸出来）。
 * - memoryExecutor：记忆写侧管线专用，慢且非交互，隔离出去防止拖垮交互路径。
 *
 * 注意：自定义任何 Executor bean 都会让 Boot 的 TaskExecutionAutoConfiguration 退避，
 * 所以 taskExecutor 必须在这里显式声明（同时挂 applicationTaskExecutor 别名，
 * 供 Spring MVC 异步请求等框架内部使用方解析）。
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("awd-async-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean("memoryExecutor")
    public ThreadPoolTaskExecutor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("awd-memory-");
        // 记忆管线丢一两条不致命，进程退出不等它
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
