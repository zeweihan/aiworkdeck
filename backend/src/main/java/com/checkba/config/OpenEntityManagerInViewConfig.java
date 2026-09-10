// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open-EntityManager-in-View，但 SSE 长连接端点除外（v0.38.3 走查 D1）。
 *
 * <p>spring.jpa.open-in-view 在本仓从来没配，走的是 Boot 默认的 true：每个请求绑一个
 * EntityManager，控制器里一旦查过库，那条 JDBC 连接就跟着 EntityManager 活到请求结束
 * （Spring 下 Hibernate 的连接模式是 DELAYED_ACQUISITION_AND_HOLD）。普通请求几十毫秒，
 * 无所谓；{@code GET /api/agent/connect/{id}} 是 SseEmitter 异步请求，归属校验查一次库，
 * 连接就被占到整条流结束——流开着占 30 分钟，客户端断开走 onError 路径时 OSIV 的
 * AsyncRequestInterceptor 根本不关 EntityManager（DeferredResult 自己的生命周期拦截器先
 * 返回 false，OSIV 的 handleError 轮不到），连接从此不还。池子一共 10 条，
 * 切几次会话就满，之后所有接口先等 30 秒再 500（SseConnectPoolReleaseTest 复现）。
 *
 * <p>刻意<b>不全局关闭</b> OSIV：别处可能依赖视图层懒加载，全关会在意想不到的地方抛
 * LazyInitializationException。这里只是替换 Boot 自动配置的那个拦截器——Boot 的
 * JpaWebConfiguration 带 {@code @ConditionalOnMissingBean(OpenEntityManagerInViewInterceptor)}，
 * 我们声明了同类型 bean 它就自动退让；注册时排除 SSE 路径，其余路径行为与原来完全一致。
 * 条件与 Boot 原配置同口径：有人显式把 open-in-view 关掉时，本配置也不生效。
 *
 * <p>SSE 端点不再持有 EntityManager 后，connect 里的查询各自走仓储方法自带的只读事务，
 * 查完即还连接。connect 只读实体的基本字段，不存在懒加载。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring.jpa", name = "open-in-view", havingValue = "true", matchIfMissing = true)
public class OpenEntityManagerInViewConfig implements WebMvcConfigurer {

    /** 长连接异步端点：不许在流的生命周期里持有 EntityManager（及其 JDBC 连接）。 */
    static final String[] LONG_LIVED_STREAM_PATHS = {"/api/agent/connect/**"};

    @Bean
    public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addWebRequestInterceptor(openEntityManagerInViewInterceptor())
                .excludePathPatterns(LONG_LIVED_STREAM_PATHS);
    }
}
