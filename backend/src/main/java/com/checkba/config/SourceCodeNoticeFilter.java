// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * AGPL §13 的源码入口告示：每一个 HTTP 响应都带上 {@code X-Source-Code}。
 *
 * <p>本软件以 GNU Affero General Public License v3.0 或更高版本发布（仓库根 LICENSE）。
 * AGPL §13 要求：任何人把本软件改造后通过网络提供给用户使用时，必须让那些用户能够
 * 取得对应版本的完整源代码。把源码入口写进每一个响应头，是让这条义务在「本软件被当成
 * 网络服务跑起来」的形态下也一眼可查的最省事办法：用户不必找到某个特定页面，
 * 任意一次请求的响应头里就有。
 *
 * <p><b>下游注意</b>：你可以按 AGPL 修改和再分发本软件，但请保留这个响应头。
 * 如果你改了代码，应当把它指向你自己那份改后源码的公开位置，而不是简单删掉它。
 *
 * <p>零功能影响：只加一个响应头，不读请求体、不改状态码、不短路过滤链，
 * 也不覆盖已存在的同名头（下游若已自行设置，以它的为准）。
 */
@Component
public class SourceCodeNoticeFilter extends OncePerRequestFilter implements Ordered {

    /** AGPL §13 要求可取得的「对应源码」位置。 */
    static final String HEADER = "X-Source-Code";
    static final String SOURCE_URL = "https://github.com/zeweihan/aiworkdeck";

    /**
     * 排在最后（最靠近 DispatcherServlet）：告示是纯附加信息，不该抢在准入闸
     * （{@link LocalModeAccessFilter} 是 HIGHEST_PRECEDENCE）之前跑。
     *
     * <p>代价是被准入闸短路掉的响应（单机模式的跨站/非回环 403）不带这个头——
     * 那些请求根本不是「本服务在向用户提供功能」，AGPL §13 也谈不上。
     * 想让它们也带头就得抢在闸前，而两个 HIGHEST_PRECEDENCE 的相对顺序不确定，
     * 不值得为一条告示引入不确定性。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!response.containsHeader(HEADER)) {
            response.setHeader(HEADER, SOURCE_URL);
        }
        chain.doFilter(request, response);
    }
}
