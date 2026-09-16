// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import com.checkba.service.AppLanguageScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求级应用语言：把 {@value #HEADER} 头声明的界面语言装进 {@link AppLanguageScope}，
 * 这一整条请求处理链上产出的用户可见文案（{@code LangText.of(...)}、懒建项目名、
 * 错误提示）随之切换。
 *
 * <p><b>为什么不是改全局设置</b>：{@code app.language} 是 system_setting 里的单值，
 * 云后端是多租户——中文用户与英文用户共用一个进程，谁写谁就把全体翻了一遍。
 * 覆盖层只影响声明它的那一次请求。
 *
 * <p><b>不带头 = 完全不变</b>：桌面端从不发这个头，走的仍是「前端设置写透 system_setting」
 * 那条老链路，行为逐字节一致。归一化见 {@link AppLanguageScope#normalize}：认不出来的值
 * （{@code ja-JP}、乱码）一律当没声明处理，不报错——语言不是准入条件。
 *
 * <p><b>覆盖不到异步编排</b>：{@code POST /api/agent/chat} 的编排循环跑在 taskExecutor
 * 池线程上，ThreadLocal 不跟着走。那条路的语言由 {@code AgentChatRequest.appLanguage}
 * 随请求体带过去，在 {@code AgentOrchestrator.launchTurn} 重建作用域。
 */
@Component
public class AppLanguageRequestFilter extends OncePerRequestFilter implements Ordered {

    /** 客户端声明界面语言的请求头（zh-CN / en-US；容忍 zh / en / en-GB 等写法）。 */
    public static final String HEADER = "X-App-Language";

    /**
     * 排在准入闸（{@link LocalModeAccessFilter}）与 CORS 之后、业务之前：
     * 语言只影响文案，不该参与任何准入判断。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String declared = request.getHeader(HEADER);
        if (AppLanguageScope.normalize(declared) == null) {
            // 未声明 / 认不出来：不建作用域，连 ThreadLocal 都不碰（桌面端走的就是这条）
            chain.doFilter(request, response);
            return;
        }
        // 受检异常穿不过 Runnable，包一层再原样抛回容器
        try {
            AppLanguageScope.run(declared, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new FilterChainFailure(e);
                }
            });
        } catch (FilterChainFailure e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw (ServletException) e.getCause();
        }
    }

    /** 仅用于把 doFilter 的受检异常抬出 lambda，不对外暴露。 */
    private static final class FilterChainFailure extends RuntimeException {
        FilterChainFailure(Exception cause) {
            super(cause);
        }
    }
}
