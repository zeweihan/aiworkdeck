// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGPL §13 源码入口告示（{@link SourceCodeNoticeFilter}）：任意端点的响应都必须带
 * {@code X-Source-Code}，GET/POST、200/500 一视同仁。
 *
 * <p>把过滤器摘掉或把 URL 改成别的，这里立刻转红。
 */
class SourceCodeNoticeFilterTest {

    /** 随便一个端点：告示与业务无关，任何 URL 都该带上。 */
    @RestController
    static class AnyController {
        @GetMapping("/api/anything")
        String ok() {
            return "ok";
        }

        @PostMapping("/api/echo")
        String echo() {
            return "echo";
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AnyController())
                .addFilters(new SourceCodeNoticeFilter())
                .build();
    }

    @Test
    void getResponseCarriesSourceCodeHeader() throws Exception {
        mvc().perform(get("/api/anything"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Source-Code", "https://github.com/zeweihan/aiworkdeck"));
    }

    @Test
    void postResponseCarriesSourceCodeHeader() throws Exception {
        mvc().perform(post("/api/echo").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Source-Code", "https://github.com/zeweihan/aiworkdeck"));
    }

    /** 非成功响应也是「网络服务给出的响应」，AGPL §13 的告示不该只在成功路径上有。 */
    @Test
    void notFoundResponseAlsoCarriesSourceCodeHeader() throws Exception {
        mvc().perform(get("/api/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Source-Code", "https://github.com/zeweihan/aiworkdeck"));
    }

    /** 常量本身也锁一道：下游改成自己的源码地址是允许的，我们自己改错了要能发现。 */
    @Test
    void headerNameAndUrlAreTheDeclaredContract() {
        assertEquals("X-Source-Code", SourceCodeNoticeFilter.HEADER);
        assertEquals("https://github.com/zeweihan/aiworkdeck", SourceCodeNoticeFilter.SOURCE_URL);
    }
}
