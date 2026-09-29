// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * langchain4j 0.36 怎么对待「没有 @P 的参数」——这条测试就是那个问题的答案（dev-board#1065 T-21）。
 *
 * <p>答案：<b>标成 required</b>，而且没有 description。审计时 13 个工具的参数全无 @P，
 * 其中 {@code law_search_keyword(title, fulltext)} 两个都是可选的（实现里各自判空），
 * 下发的 schema 却说两个都必填——模型只好硬凑一个值，或者干脆换工具。
 * 所以可选参数必须显式写 {@code @P(value = ..., required = false)}，只写 @P 不够（缺省 required = true）。
 *
 * <p>哪天升级 langchain4j 改了这个缺省，这条会先红，届时回头检查那 13 个工具的 @P 是否还需要。
 */
class ToolParameterRequiredDefaultTest {

    static class Fixture {
        @Tool("fixture")
        public String bare(String plain) {
            return plain;
        }

        @Tool("fixture")
        public String annotated(@P("说明") String withP, @P(value = "说明", required = false) String optional) {
            return withP + optional;
        }
    }

    private static ToolSpecification spec(String name) throws NoSuchMethodException {
        Method method = name.equals("bare")
                ? Fixture.class.getMethod("bare", String.class)
                : Fixture.class.getMethod("annotated", String.class, String.class);
        return ToolSpecifications.toolSpecificationFrom(method);
    }

    @Test
    @DisplayName("langchain4j 0.36：不带 @P 的参数被标成 required，且没有任何说明")
    void unannotatedParameterIsRequiredAndUndescribed() throws Exception {
        ToolSpecification bare = spec("bare");
        assertEquals(java.util.List.of("plain"), bare.parameters().required(),
                "编译带 -parameters 时参数名原样进 schema；不带 @P 的参数一律进 required");
        assertFalse(String.valueOf(bare.parameters().properties().get("plain")).contains("说明"));
    }

    @Test
    @DisplayName("@P 缺省也是 required；只有 required = false 才会退出 required 列表")
    void onlyExplicitRequiredFalseMakesAParameterOptional() throws Exception {
        ToolSpecification annotated = spec("annotated");
        assertTrue(annotated.parameters().required().contains("withP"));
        assertFalse(annotated.parameters().required().contains("optional"));
    }
}
