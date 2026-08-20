package com.checkba.service.ai.tools;

import com.checkba.service.QichachaService;
import com.checkba.service.TushareService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产品发布视频演示桩（dev-board #59）：{@code ai.tools.enterprise-demo-fixtures} 未配置时
 * qichacha_query 必须原样走真实查询——默认关闭，不能悄悄改变现有行为。
 * 配置了目录且命中固定公司名时，直接回预置 JSON，不打真实网关。
 *
 * <p>纯 Mockito 单测，不接 Spring 上下文、不碰任何数据库。
 */
class EnterpriseDataToolsDemoFixtureTest {

    private static EnterpriseDataTools toolsWith(QichachaService qichachaService, String fixturesDir) {
        EnterpriseDataTools tools = new EnterpriseDataTools(qichachaService, mock(TushareService.class));
        ReflectionTestUtils.setField(tools, "enterpriseDemoFixturesDir", fixturesDir);
        return tools;
    }

    @Test
    @DisplayName("未配置演示目录时（默认）：原样调用真实企查查服务")
    void demoDisabledByDefaultFallsThroughToRealService() throws Exception {
        QichachaService qichachaService = mock(QichachaService.class);
        when(qichachaService.queryEciInfoJson("某真实公司")).thenReturn("{\"Name\":\"某真实公司\"}");

        String result = toolsWith(qichachaService, "").qichacha_query("某真实公司");

        assertEquals("{\"Name\":\"某真实公司\"}", result);
        verify(qichachaService).queryEciInfoJson("某真实公司");
    }

    @Test
    @DisplayName("配置了演示目录但公司名未命中任何 fixture：仍走真实查询")
    void configuredButNoMatchFallsThroughToRealService(@TempDir Path tempDir) throws Exception {
        QichachaService qichachaService = mock(QichachaService.class);
        when(qichachaService.queryEciInfoJson("未收录的公司")).thenReturn("{\"Name\":\"未收录的公司\"}");

        String result = toolsWith(qichachaService, tempDir.toString()).qichacha_query("未收录的公司");

        assertEquals("{\"Name\":\"未收录的公司\"}", result);
        verify(qichachaService).queryEciInfoJson("未收录的公司");
    }

    @Test
    @DisplayName("配置了演示目录且命中公司名：返回预置 JSON，不打真实网关")
    void configuredAndMatchedReturnsFixtureWithoutCallingRealService(@TempDir Path tempDir) throws IOException {
        String fixtureJson = "{\"Name\":\"星澜科技（上海）有限公司\",\"OperName\":\"陈曦\"}";
        Files.writeString(
                tempDir.resolve("星澜科技（上海）有限公司.json"),
                fixtureJson,
                StandardCharsets.UTF_8);
        QichachaService qichachaService = mock(QichachaService.class);

        String result = toolsWith(qichachaService, tempDir.toString()).qichacha_query("星澜科技（上海）有限公司");

        assertEquals(fixtureJson, result);
        verify(qichachaService, never()).queryEciInfoJson(org.mockito.ArgumentMatchers.anyString());
    }
}
