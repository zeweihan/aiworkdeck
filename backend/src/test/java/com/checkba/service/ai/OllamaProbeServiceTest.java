package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本地 Ollama 探测的离线单测。
 *
 * HTTP 往返走 {@link OllamaProbeService#get(String)} 这个测试钩子，
 * 绝不真发请求——否则结果取决于跑测试的机器上装没装 Ollama。
 */
class OllamaProbeServiceTest {

    private AiModelProperties props;
    private SystemSettingService settings;

    /** 可编程的探测服务：记录请求过的 URL，按脚本回一个 HttpReply */
    private static class StubProbe extends OllamaProbeService {
        final List<String> requestedUrls = new ArrayList<>();
        HttpReply reply = new HttpReply(HttpReply.NETWORK_FAILURE, null);

        StubProbe(AiModelProperties props, SystemSettingService settings) {
            super(props, settings);
        }

        @Override
        HttpReply get(String url) {
            requestedUrls.add(url);
            return reply;
        }
    }

    private StubProbe probe;

    @BeforeEach
    void setUp() {
        props = new AiModelProperties();
        props.getOllama().setBaseUrl("http://localhost:11434");
        props.getOllama().setModelName("qwen3-vl:8b");
        settings = mock(SystemSettingService.class);
        // 缺省：DB 里没有覆盖（口径与 ChatModelFactory 一致，null/空白都算未配置）
        when(settings.get(any(), any())).thenReturn(null);
        probe = new StubProbe(props, settings);
    }

    private static String tagsBody(String... names) {
        StringBuilder sb = new StringBuilder("{\"models\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(names[i]).append("\",\"size\":123,\"digest\":\"abc\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("服务在跑且目标模型已 pull：READY，不给命令，并提示只支持 ASK 模式")
    void readyWhenModelPresent() {
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("qwen3-vl:8b", "nomic-embed-text:latest"));

        OllamaProbeService.ProbeResult r = probe.probe();

        assertEquals(OllamaProbeService.Status.READY, r.status());
        assertEquals("qwen3-vl:8b", r.targetModel());
        assertEquals(List.of("qwen3-vl:8b", "nomic-embed-text:latest"), r.installedModels());
        assertNull(r.command(), "就绪时不该给用户任何终端命令");
        // 决策 2：Ollama 只支持 ASK，这句必须出现在就绪分支，否则用户会以为本地档能跑 Agent
        assertTrue(r.nextStep().contains("ASK"), "就绪提示应说明只支持 ASK 模式，实际: " + r.nextStep());
        // 探测只打 /api/tags，且用配置里的 baseUrl
        assertEquals(List.of("http://localhost:11434/api/tags"), probe.requestedUrls);
    }

    @Test
    @DisplayName("服务在跑但模型没 pull：MODEL_MISSING，原样带出 ollama pull 命令")
    void modelMissingCarriesPullCommand() {
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("llama3:latest"));

        OllamaProbeService.ProbeResult r = probe.probe();

        assertEquals(OllamaProbeService.Status.MODEL_MISSING, r.status());
        assertEquals("ollama pull qwen3-vl:8b", r.command());
        assertEquals(List.of("llama3:latest"), r.installedModels(),
                "已下载清单要带出来，向导要用它渲染可选模型");
    }

    @Test
    @DisplayName("连不上：SERVICE_DOWN + ollama serve，不抛异常")
    void serviceDownWhenNetworkFails() {
        probe.reply = new OllamaProbeService.HttpReply(
                OllamaProbeService.HttpReply.NETWORK_FAILURE, null);

        OllamaProbeService.ProbeResult r = probe.probe();

        assertEquals(OllamaProbeService.Status.SERVICE_DOWN, r.status());
        assertEquals("ollama serve", r.command());
        assertEquals(List.of(), r.installedModels());
        assertNotNull(r.message());
    }

    @Test
    @DisplayName("非 200 与无法解析的响应都归为 SERVICE_DOWN（对用户是同一件事：现在用不了）")
    void nonOkAndUnparsableBothServiceDown() {
        probe.reply = new OllamaProbeService.HttpReply(500, "boom");
        assertEquals(OllamaProbeService.Status.SERVICE_DOWN, probe.probe().status());

        probe.reply = new OllamaProbeService.HttpReply(200, "<html>not json</html>");
        assertEquals(OllamaProbeService.Status.SERVICE_DOWN, probe.probe().status());

        // 200 但结构不对（models 不是数组）
        probe.reply = new OllamaProbeService.HttpReply(200, "{\"models\":\"oops\"}");
        assertEquals(OllamaProbeService.Status.SERVICE_DOWN, probe.probe().status());
    }

    @Test
    @DisplayName("空 models 数组：服务在跑但一个模型都没有，是 MODEL_MISSING 而不是 SERVICE_DOWN")
    void emptyModelListIsModelMissing() {
        probe.reply = new OllamaProbeService.HttpReply(200, "{\"models\":[]}");

        OllamaProbeService.ProbeResult r = probe.probe();

        assertEquals(OllamaProbeService.Status.MODEL_MISSING, r.status());
        assertEquals("ollama pull qwen3-vl:8b", r.command());
    }

    @Test
    @DisplayName("模型名比对补默认 tag：配 llama3、本机存的是 llama3:latest 算命中")
    void defaultTagIsNormalizedOnBothSides() {
        props.getOllama().setModelName("llama3");
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("llama3:latest"));
        assertEquals(OllamaProbeService.Status.READY, probe.probe().status());

        // 反向：配的带 :latest，服务端返回不带 tag（Ollama 恒带，但别的兼容实现未必）
        props.getOllama().setModelName("llama3:latest");
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("llama3"));
        assertEquals(OllamaProbeService.Status.READY, probe.probe().status());

        // 不同 tag 不算命中：8b 和 70b 是两个模型
        props.getOllama().setModelName("llama3:70b");
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("llama3:8b"));
        assertEquals(OllamaProbeService.Status.MODEL_MISSING, probe.probe().status());
    }

    @Test
    @DisplayName("目标模型 DB 优先于 yml，空白值视为未配置")
    void settingOverridesYmlModelName() {
        when(settings.get(eq(OllamaProbeService.SETTING_MODEL), any())).thenReturn("qwen3:14b");
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("qwen3-vl:8b"));

        OllamaProbeService.ProbeResult r = probe.probe();
        assertEquals("qwen3:14b", r.targetModel());
        assertEquals(OllamaProbeService.Status.MODEL_MISSING, r.status());

        when(settings.get(eq(OllamaProbeService.SETTING_MODEL), any())).thenReturn("   ");
        assertEquals("qwen3-vl:8b", probe.probe().targetModel(), "空白设置值应回退 yml");
    }

    @Test
    @DisplayName("入参 model 优先于 DB 与 yml（向导里还没保存就先试一个模型）")
    void explicitModelOverridesEverything() {
        when(settings.get(eq(OllamaProbeService.SETTING_MODEL), any())).thenReturn("qwen3:14b");
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("gemma3:12b"));

        OllamaProbeService.ProbeResult r = probe.probe("  gemma3:12b  ");
        assertEquals("gemma3:12b", r.targetModel(), "入参应被 trim 后使用");
        assertEquals(OllamaProbeService.Status.READY, r.status());
    }

    @Test
    @DisplayName("baseUrl 末尾斜杠不会拼出 //api/tags")
    void trailingSlashInBaseUrlIsTrimmed() {
        props.getOllama().setBaseUrl("http://127.0.0.1:11434/");
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("qwen3-vl:8b"));

        OllamaProbeService.ProbeResult r = probe.probe();
        assertEquals("http://127.0.0.1:11434", r.baseUrl());
        assertEquals(List.of("http://127.0.0.1:11434/api/tags"), probe.requestedUrls);
    }

    @Test
    @DisplayName("三种状态的用户文案都不含账户判定禁用子串（api.js 会据此清会话）")
    void userFacingTextsAvoidSessionKillingSubstrings() {
        List<OllamaProbeService.ProbeResult> all = new ArrayList<>();
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("qwen3-vl:8b"));
        all.add(probe.probe());
        probe.reply = new OllamaProbeService.HttpReply(200, tagsBody("llama3:latest"));
        all.add(probe.probe());
        probe.reply = new OllamaProbeService.HttpReply(
                OllamaProbeService.HttpReply.NETWORK_FAILURE, null);
        all.add(probe.probe());

        for (OllamaProbeService.ProbeResult r : all) {
            String text = r.message() + "|" + r.nextStep();
            for (String banned : List.of("登录", "未授权", "请先")) {
                assertFalse(text.contains(banned),
                        r.status() + " 文案不得含「" + banned + "」（前端会当成掉线清会话），实际: " + text);
            }
        }
    }
}
