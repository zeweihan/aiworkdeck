package com.checkba.service.platform;

import com.checkba.service.OcrService;
import com.checkba.service.QichachaService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.TushareService;
import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.service.ai.tools.EnterpriseDataTools;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.service.ai.tools.PythonTools;
import com.checkba.service.ocr.OcrResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P4 五家服务的双档路由。
 *
 * <p>每家守同样的三条，因为每家都能用同样的三种方式坏掉：
 * <ol>
 *   <li><b>平台档不碰 BYOK 实现</b>——碰了就是拿网关的账去花用户的 Key，或者反过来；</li>
 *   <li><b>BYOK 档一次网关都不打</b>——打了就是给自备订阅的用户重复计费（设计 §5.2.1 那两类受害者）；</li>
 *   <li><b>平台档失败绝不静默回落 BYOK</b>（地雷 8 / 27）——回落会去花用户自己的 Key，
 *       而用户看到的是「好像成功了」，账单在别处。</li>
 * </ol>
 */
class ExternalServiceDualTierRoutingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 档位解析器的桩：直接钉死某项服务当前是哪一档。 */
    private static ExternalProviderResolver resolverWith(String service, ExternalServiceProvider tier) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.equals(ExternalProviderResolver.providerKey(service))
                    ? tier.settingValue()
                    : inv.getArgument(1);
        });
        return new ExternalProviderResolver(settings, true);
    }

    private static PlatformGatewayClient gatewayReturning(String dataJson) {
        PlatformGatewayClient client = mock(PlatformGatewayClient.class);
        try {
            when(client.call(anyString(), anyString(), anyMap(), anyInt()))
                    .thenReturn(new PlatformGatewayClient.Result(MAPPER.readTree(dataJson), 3, 1, "call"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return client;
    }

    private static PlatformGatewayClient gatewayFailing(GatewayException.Kind kind) {
        PlatformGatewayClient client = mock(PlatformGatewayClient.class);
        when(client.call(anyString(), anyString(), anyMap(), anyInt()))
                .thenThrow(new GatewayException(kind, "该服务暂未开放，可在系统管理里改用自己的 Key"));
        return client;
    }

    // =======================================================================
    @Nested
    @DisplayName("OCR")
    class Ocr {

        private OcrService service(ExternalServiceProvider tier, PlatformGatewayClient gateway,
                                   SystemSettingService settings) {
            return new OcrService(settings, resolverWith(ExternalServiceProvider.OCR, tier), gateway);
        }

        /** 带一套「用户自己填过的阿里云 Key」的设置，用来验 BYOK 档确实走了老路。 */
        private SystemSettingService byokSettings() {
            SystemSettingService settings = mock(SystemSettingService.class);
            when(settings.get(anyString(), any())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                if (key.equals("external.aliyunOcr.accessKeyId")) return "LTAI-fake";
                if (key.equals("external.aliyunOcr.accessKeySecret")) return "secret-fake";
                return inv.getArgument(1);
            });
            return settings;
        }

        @Test
        @DisplayName("平台档：走网关的 ocr/recognize，结果与自备 Key 档同一形状")
        void platformCallsGateway() {
            PlatformGatewayClient gateway =
                    gatewayReturning("{\"content\":\"识别正文\",\"raw\":\"{\\\"a\\\":1}\"}");
            SystemSettingService settings = mock(SystemSettingService.class);
            when(settings.get(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));

            OcrResult result = service(ExternalServiceProvider.PLATFORM, gateway, settings)
                    .recognizeGeneral(java.util.Base64.getEncoder().encodeToString("png".getBytes()));

            assertEquals("识别正文", result.getText());
            assertEquals("{\"a\":1}", result.getRaw());
            verify(gateway).call(eq("ocr"), eq("recognize"), anyMap(), anyInt());
            // 平台档不该去读用户的阿里云凭证——读了说明分档没分干净
            verify(settings, never()).get(eq("external.aliyunOcr.accessKeyId"), any());
        }

        @Test
        @DisplayName("自备 Key 档：一次网关都不打（打了就是给已付订阅的用户重复计费）")
        void byokNeverTouchesGateway() {
            PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
            SystemSettingService settings = byokSettings();

            // 没有真凭证，真正的阿里云调用必然失败——这里只关心「有没有走网关」
            assertThrows(RuntimeException.class,
                    () -> service(ExternalServiceProvider.BYOK, gateway, settings)
                            .recognizeGeneral(java.util.Base64.getEncoder().encodeToString("png".getBytes())));

            verifyNoInteractions(gateway);
        }

        @Test
        @DisplayName("平台档失败：原样抛 GatewayException，绝不回落去花用户的 Key")
        void platformFailureDoesNotFallBack() {
            PlatformGatewayClient gateway = gatewayFailing(GatewayException.Kind.SERVICE_DISABLED);
            SystemSettingService settings = byokSettings();

            GatewayException e = assertThrows(GatewayException.class,
                    () -> service(ExternalServiceProvider.PLATFORM, gateway, settings)
                            .recognizeGeneral(java.util.Base64.getEncoder().encodeToString("png".getBytes())));

            assertEquals(GatewayException.Kind.SERVICE_DISABLED, e.getKind());
            // 回落会拿用户自己的阿里云账号去跑，账单落在他那边而界面上看不出来
            verify(settings, never()).get(eq("external.aliyunOcr.accessKeyId"), any());
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("企查查")
    class Qichacha {

        private QichachaService service(ExternalServiceProvider tier, PlatformGatewayClient gateway) {
            QichachaService svc = new QichachaService();
            SystemSettingService settings = mock(SystemSettingService.class);
            when(settings.get(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
            ReflectionTestUtils.setField(svc, "systemSettingService", settings);
            ReflectionTestUtils.setField(svc, "externalProviderResolver",
                    resolverWith(ExternalServiceProvider.QICHACHA, tier));
            ReflectionTestUtils.setField(svc, "platformGatewayClient", gateway);
            ReflectionTestUtils.setField(svc, "defaultAppKey", "qcc-key");
            ReflectionTestUtils.setField(svc, "defaultSecretKey", "qcc-secret");
            // 指向一个必然连不上的地址：BYOK 档只要不打网关就算过
            ReflectionTestUtils.setField(svc, "defaultBaseUrl", "http://127.0.0.1:1");
            return svc;
        }

        @Test
        @DisplayName("平台档：走网关的 qichacha/eci_info，Result 映射成 DTO")
        void platformCallsGateway() {
            PlatformGatewayClient gateway = gatewayReturning(
                    "{\"Status\":\"200\",\"Result\":{\"Name\":\"某某有限公司\",\"RegistCapi\":\"1000万\"}}");

            var dto = service(ExternalServiceProvider.PLATFORM, gateway).searchCompany("某某有限公司", "TARGET");

            assertEquals("某某有限公司", dto.getName());
            assertEquals("1000万", dto.getRegisteredCapital());
            verify(gateway).call(eq("qichacha"), eq("eci_info"), anyMap(), anyInt());
        }

        @Test
        @DisplayName("自备 Key 档：一次网关都不打")
        void byokNeverTouchesGateway() {
            PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
            assertThrows(RuntimeException.class,
                    () -> service(ExternalServiceProvider.BYOK, gateway).searchCompany("某某有限公司", "TARGET"));
            verifyNoInteractions(gateway);
        }

        @Test
        @DisplayName("平台档失败：GatewayException 不被包成「外部数据服务暂不可用」，kind 必须活着到调用方")
        void platformFailureKeepsKind() {
            PlatformGatewayClient gateway = gatewayFailing(GatewayException.Kind.SERVICE_DISABLED);
            GatewayException e = assertThrows(GatewayException.class,
                    () -> service(ExternalServiceProvider.PLATFORM, gateway).searchCompany("某某有限公司", "TARGET"));
            assertEquals(GatewayException.Kind.SERVICE_DISABLED, e.getKind());
        }

        // ---- 知识产权类（dev-board#179）：op = MCP 工具名，网关回 {raw:...}，
        // 解析与法宝共用 McpResponseParser ----

        @Test
        @DisplayName("IPR 平台档：走网关的 qichacha/get_trademark_info，SSE 信封解析出正文")
        void iprPlatformCallsGatewayAndParsesSse() {
            String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"商标 3 条\"}]}}\n";
            PlatformGatewayClient gateway = gatewayReturning(
                    "{\"raw\":" + cn.hutool.json.JSONUtil.quote(sse) + "}");

            String text = service(ExternalServiceProvider.PLATFORM, gateway)
                    .queryIprJson("get_trademark_info", "某某有限公司");

            assertEquals("商标 3 条", text);
            verify(gateway).call(eq("qichacha"), eq("get_trademark_info"), anyMap(), anyInt());
        }

        @Test
        @DisplayName("IPR 非平台档：不打网关，抛可读的「仅官方版提供」")
        void iprNonPlatformRefusesWithoutGateway() {
            PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service(ExternalServiceProvider.BYOK, gateway)
                            .queryIprJson("get_trademark_info", "某某有限公司"));
            assertTrue(e.getMessage().contains("仅官方版平台通道"));
            verifyNoInteractions(gateway);
        }

        @Test
        @DisplayName("IPR 平台档失败：kind 活着到调用方")
        void iprPlatformFailureKeepsKind() {
            PlatformGatewayClient gateway = gatewayFailing(GatewayException.Kind.NO_CREDITS);
            GatewayException e = assertThrows(GatewayException.class,
                    () -> service(ExternalServiceProvider.PLATFORM, gateway)
                            .queryIprJson("get_patent_info", "某某有限公司"));
            assertEquals(GatewayException.Kind.NO_CREDITS, e.getKind());
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Tushare")
    class Tushare {

        private TushareService service(ExternalServiceProvider tier, PlatformGatewayClient gateway) {
            TushareService svc = new TushareService();
            SystemSettingService settings = mock(SystemSettingService.class);
            when(settings.get(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
            ReflectionTestUtils.setField(svc, "systemSettingService", settings);
            ReflectionTestUtils.setField(svc, "externalProviderResolver",
                    resolverWith(ExternalServiceProvider.TUSHARE, tier));
            ReflectionTestUtils.setField(svc, "platformGatewayClient", gateway);
            ReflectionTestUtils.setField(svc, "defaultTushareToken", "ts-token");
            ReflectionTestUtils.setField(svc, "defaultTushareApiUrl", "http://127.0.0.1:1");
            return svc;
        }

        @Test
        @DisplayName("平台档：走网关的 tushare/query，响应形状与自备 token 档一致")
        void platformCallsGateway() {
            PlatformGatewayClient gateway = gatewayReturning(
                    "{\"code\":0,\"data\":{\"fields\":[\"ts_code\"],\"items\":[[\"000001.SZ\"]]}}");

            String json = service(ExternalServiceProvider.PLATFORM, gateway)
                    .queryJson("stock_basic", Map.of("list_status", "L"), "ts_code");

            assertTrue(json.contains("000001.SZ"));
            verify(gateway).call(eq("tushare"), eq("query"), anyMap(), anyInt());
        }

        @Test
        @DisplayName("自备 token 档：一次网关都不打，失败仍是既有的「回 null」语义")
        void byokNeverTouchesGateway() {
            PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
            // 上游连不上，既有实现回 null，上层解析函数据此当「没数据」——这条语义一字未改
            assertEquals("", service(ExternalServiceProvider.BYOK, gateway)
                    .queryJson("stock_basic", Map.of(), "ts_code"));
            verifyNoInteractions(gateway);
        }

        @Test
        @DisplayName("平台档失败抛出而不是回 null——null 会让「余额不足」伪装成一次查不到")
        void platformFailureIsNotSwallowedAsEmpty() {
            PlatformGatewayClient gateway = gatewayFailing(GatewayException.Kind.NO_CREDITS);
            GatewayException e = assertThrows(GatewayException.class,
                    () -> service(ExternalServiceProvider.PLATFORM, gateway)
                            .queryJson("stock_basic", Map.of(), "ts_code"));
            assertEquals(GatewayException.Kind.NO_CREDITS, e.getKind());
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("北大法宝（三个 MCP server）")
    class Pkulaw {

        private LegalTools tools(ExternalServiceProvider tier, PlatformGatewayClient gateway,
                                 McpClientService mcp) {
            return new LegalTools(null,
                    new com.checkba.service.legal.PkulawChannel(
                            mcp, resolverWith(ExternalServiceProvider.PKULAW, tier), gateway),
                    null, null);
        }

        @Test
        @DisplayName("平台档：走网关，MCP 响应正文由桌面端既有的解析器解析")
        void platformCallsGatewayAndParsesLocally() {
            McpClientService mcp = mock(McpClientService.class);
            PlatformGatewayClient gateway = gatewayReturning(
                    "{\"raw\":\"{\\\"result\\\":{\\\"content\\\":[{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"第五百零九条\\\"}]}}\"}");

            String out = tools(ExternalServiceProvider.PLATFORM, gateway, mcp).law_search("合同履行");

            assertTrue(out.contains("第五百零九条"), "实际为：" + out);
            // op 就是法宝的工具名；端点写死在服务端，客户端点不了别处
            verify(gateway).call(eq("pkulaw"), eq("search_article"), anyMap(), anyInt());
            verifyNoInteractions(mcp);
        }

        @Test
        @DisplayName("自备 token 档：仍直接打 MCP server，一次网关都不打")
        void byokStillUsesMcpDirectly() {
            McpClientService mcp = mock(McpClientService.class);
            when(mcp.callTool(anyString(), anyString(), anyMap())).thenReturn("mcp-result");
            PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);

            assertEquals("mcp-result",
                    tools(ExternalServiceProvider.BYOK, gateway, mcp).law_search("合同履行"));

            verify(mcp).callTool(eq("pkulaw-semantic"), eq("search_article"), anyMap());
            verifyNoInteractions(gateway);
        }

        @Test
        @DisplayName("四个工具各自打到正确的 op")
        void everyToolMapsToItsOp() {
            McpClientService mcp = mock(McpClientService.class);
            PlatformGatewayClient gateway = gatewayReturning("{\"raw\":\"{}\"}");
            LegalTools t = tools(ExternalServiceProvider.PLATFORM, gateway, mcp);

            t.law_search("q");
            t.law_search_keyword("民法典", null);
            t.law_recognition("依据合同法");
            t.get_law_article("民法典", "509");

            for (String op : List.of("search_article", "get_law_list", "law_recognition", "get_article")) {
                verify(gateway).call(eq("pkulaw"), eq(op), anyMap(), anyInt());
            }
        }

        @Test
        @DisplayName("平台档失败：给模型一段说明文本，不抛异常打断整轮对话，也不回落 MCP")
        void failureExplainsWithoutBreakingTheTurn() {
            McpClientService mcp = mock(McpClientService.class);
            PlatformGatewayClient gateway = gatewayFailing(GatewayException.Kind.SERVICE_DISABLED);

            String out = tools(ExternalServiceProvider.PLATFORM, gateway, mcp).law_search("合同履行");

            assertTrue(out.contains("法规检索本次不可用"), "实际为：" + out);
            assertTrue(out.contains("基于已有信息继续"), "实际为：" + out);
            // 回落到 MCP 会拿用户自己的订阅 token 去跑；抛异常则会让一次检索失败变成一次对话失败
            verifyNoInteractions(mcp);
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("非 Java 出站路径：PythonTools 的凭证注入")
    class PythonCredentials {

        private PythonTools tools(ExternalServiceProvider tushareTier, ExternalServiceProvider qichachaTier) {
            SystemSettingService settings = mock(SystemSettingService.class);
            when(settings.get(anyString(), any())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                if (key.equals(ExternalProviderResolver.providerKey(ExternalServiceProvider.TUSHARE))) {
                    return tushareTier.settingValue();
                }
                if (key.equals(ExternalProviderResolver.providerKey(ExternalServiceProvider.QICHACHA))) {
                    return qichachaTier.settingValue();
                }
                return inv.getArgument(1);
            });
            PythonTools t = new PythonTools(null, null, settings, new ExternalProviderResolver(settings, true));
            ReflectionTestUtils.setField(t, "tushareToken", "ts-token");
            ReflectionTestUtils.setField(t, "qichachaKey", "qcc-key");
            ReflectionTestUtils.setField(t, "qichachaSecret", "qcc-secret");
            return t;
        }

        @Test
        @DisplayName("平台档：三个变量一个都不注入（凭证在官网，下发等于把公司账号发给所有人）")
        void platformInjectsNothing() {
            var env = tools(ExternalServiceProvider.PLATFORM, ExternalServiceProvider.PLATFORM)
                    .injectableCredentials();
            assertTrue(env.isEmpty(), "实际为：" + env);
        }

        @Test
        @DisplayName("自备 Key 档：与改造前逐字一致，三个变量照旧注入")
        void byokInjectsAsBefore() {
            var env = tools(ExternalServiceProvider.BYOK, ExternalServiceProvider.BYOK)
                    .injectableCredentials();
            assertEquals("ts-token", env.get("TUSHARE_TOKEN"));
            assertEquals("qcc-key", env.get("QICHACHA_KEY"));
            assertEquals("qcc-secret", env.get("QICHACHA_SECRET"));
        }

        @Test
        @DisplayName("两家档位互不影响：一家平台一家自备时只注入自备那家")
        void tiersAreIndependent() {
            var env = tools(ExternalServiceProvider.PLATFORM, ExternalServiceProvider.BYOK)
                    .injectableCredentials();
            assertFalse(env.containsKey("TUSHARE_TOKEN"));
            assertEquals("qcc-key", env.get("QICHACHA_KEY"));
        }
    }

    // =======================================================================
    @Nested
    @DisplayName("Java 侧一等工具（补上 PythonTools 停掉的那条路）")
    class FirstClassTools {

        @Test
        @DisplayName("网关失败时返回说明文本而不是抛异常——一次查询失败不该变成一次对话失败")
        void gatewayFailureDoesNotBreakTheTurn() {
            QichachaService qcc = mock(QichachaService.class);
            when(qcc.queryEciInfoJson(anyString()))
                    .thenThrow(new GatewayException(GatewayException.Kind.SERVICE_DISABLED, "该服务暂未开放"));
            TushareService ts = mock(TushareService.class);
            when(ts.queryJson(anyString(), anyMap(), anyString()))
                    .thenThrow(new GatewayException(GatewayException.Kind.NO_CREDITS, "Credits 余额不足，到官网充值后即可继续"));

            EnterpriseDataTools tools = new EnterpriseDataTools(qcc, ts);

            String a = tools.qichacha_query("某某有限公司");
            assertTrue(a.contains("本次不可用") && a.contains("基于已有信息继续"), "实际为：" + a);
            String b = tools.tushare_query("stock_basic", "{}", "ts_code");
            assertTrue(b.contains("本次不可用") && b.contains("基于已有信息继续"), "实际为：" + b);

            // 面向用户的文案一律不许命中掉线判定子串（api.js 会据此清会话）
            for (String forbidden : List.of("登录", "未授权", "请先")) {
                assertFalse(a.contains(forbidden), "qichacha_query 文案含「" + forbidden + "」：" + a);
                assertFalse(b.contains(forbidden), "tushare_query 文案含「" + forbidden + "」：" + b);
            }
        }

        @Test
        @DisplayName("参数写错时明确报错，不静默当成空参数打上游")
        void badParamsAreRejected() {
            EnterpriseDataTools tools = new EnterpriseDataTools(mock(QichachaService.class), mock(TushareService.class));
            String out = tools.tushare_query("stock_basic", "ts_code=000001", "ts_code");
            assertTrue(out.startsWith("Error:"), "实际为：" + out);
        }

        @Test
        @DisplayName("两个工具都带中文显示名（工具卡片上不该出现英文方法名）")
        void toolsHaveChineseDisplayNames() throws Exception {
            assertEquals("查询企业工商信息",
                    EnterpriseDataTools.class.getMethod("qichacha_query", String.class)
                            .getAnnotation(com.checkba.service.ai.tools.ToolMeta.class).displayName());
            assertEquals("查询金融数据",
                    EnterpriseDataTools.class.getMethod("tushare_query", String.class, String.class, String.class)
                            .getAnnotation(com.checkba.service.ai.tools.ToolMeta.class).displayName());
        }
    }
}
