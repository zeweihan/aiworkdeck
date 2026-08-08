package com.checkba.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 白名单与 OpenRouter 线上目录的对拍（默认跳过；设置 {@code RUN_LIVE_MODEL_CHECK=1} 后启用）。
 *
 * <p><b>为什么必须有</b>：{@link AllowedModels} 的单价是人手抄的，而它直接决定 BYOK 用户看到的
 * 花费估算。2026-08-08 首次对拍就抓到 5 条价格错，其中 {@code moonshotai/kimi-k2.6} 的
 * 输入价与输出价分别把用户超收了 14% 和 40%。这条测试是价格漂移的唯一护栏——
 * 上游调价、模型下线、模型停止支持 tools 都只会在这里暴露出来。
 *
 * <p><b>为什么默认跳过</b>：mvn test 默认离线可跑是硬要求（CI 与开发机都不该因为
 * openrouter.ai 抖动而红）。所以门控写法与 {@code RealLlmSmokeTest} 一致：
 * 类级 {@code @EnabledIfEnvironmentVariable} 挡住整批，另在工厂里补一层
 * {@code assumeTrue}，防止有人绕过条件扩展直跑方法。
 *
 * <p>运行：{@code RUN_LIVE_MODEL_CHECK=1 mvn test -Dtest=AllowedModelsLiveContractTest}
 *
 * <p>失败时的处理顺序：先确认线上是真的变了（浏览器打开 openrouter.ai 的模型页核对），
 * 再改 {@link AllowedModels} 的价格或把模型换掉；**不要**为了让测试变绿而放宽容差。
 */
@DisplayName("模型白名单对拍 OpenRouter 线上目录（默认跳过）")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_CHECK", matches = "1")
class AllowedModelsLiveContractTest {

    private static final String MODELS_URL = "https://openrouter.ai/api/v1/models";

    /** 单价容差：上游偶有末位舍入差异，1% 足够吸收，又抓得住 14% 这类真错。 */
    private static final double PRICE_TOLERANCE = 0.01;

    @TestFactory
    Stream<DynamicTest> everyWhitelistedModelMatchesUpstream() {
        Assumptions.assumeTrue("1".equals(System.getenv("RUN_LIVE_MODEL_CHECK")),
                "未设置 RUN_LIVE_MODEL_CHECK=1，跳过联网对拍");

        Map<String, JsonNode> upstream = fetchUpstreamCatalog();

        // 一个模型一条动态用例：价格漂移往往是批量的（上游整档调价），
        // 一条测试撞死在第一个不一致上会让人以为只错了一条
        return Stream.of(AllowedModels.values())
                .map(m -> DynamicTest.dynamicTest(m.getModelId(), () -> assertMatchesUpstream(m, upstream)));
    }

    private void assertMatchesUpstream(AllowedModels model, Map<String, JsonNode> upstream) {
        JsonNode entry = upstream.get(model.getModelId());
        assertNotNull(entry, "模型已从 OpenRouter 目录消失（下线或改名）：" + model.getModelId()
                + "。留在白名单里会让选到它的用户直接报错，必须换掉。");

        // tools 支持是白名单的构造性前提：AllowedModels 里刻意没有 toolCalling 字段，
        // 靠这条断言守。模型停止支持 tools 会让整个 Agent 模式静默退化。
        JsonNode params = entry.path("supported_parameters");
        boolean supportsTools = false;
        for (JsonNode p : params) {
            if ("tools".equals(p.asText())) {
                supportsTools = true;
                break;
            }
        }
        assertTrue(supportsTools,
                model.getModelId() + " 已不支持 tools，supported_parameters=" + params);

        // OpenRouter 的 pricing 是「每 token 的美元数」字符串，我们记的是 $/1M tokens
        AllowedModels.PriceTier first = model.getPriceTiers().get(0);
        assertPriceClose(model, "输入", first.inputPricePerM(), entry.path("pricing").path("prompt"));
        assertPriceClose(model, "输出", first.outputPricePerM(), entry.path("pricing").path("completion"));
    }

    private void assertPriceClose(AllowedModels model, String label, double oursPerM, JsonNode upstreamPerToken) {
        // 不用 asText(default)：MissingNode 的这个重载在 Jackson 里返回空串而不是默认值，
        // 会把「字段缺失」伪装成「价格 0」
        assertTrue(upstreamPerToken.isTextual() || upstreamPerToken.isNumber(),
                model.getModelId() + " 的 pricing 缺" + label + "单价，实际节点=" + upstreamPerToken);
        double upstreamPerM = Double.parseDouble(upstreamPerToken.asText()) * 1_000_000d;

        // 动态路由别名的 pricing 是 -1，那种模型本来就不该进白名单
        assertTrue(upstreamPerM >= 0,
                model.getModelId() + " 的" + label + "单价为负（动态路由别名？），静态价格表无法计价");

        if (upstreamPerM == 0d) {
            assertEquals(0d, oursPerM, model.getModelId() + " 上游" + label + "免费，我们却记了单价");
            return;
        }
        double drift = Math.abs(oursPerM - upstreamPerM) / upstreamPerM;
        assertTrue(drift <= PRICE_TOLERANCE, String.format(
                "%s 的%s单价漂移 %.1f%%：我们记 $%s/1M，线上是 $%s/1M。"
                        + "偏高就是对用户超收，偏低是低报花费，两者都要改 AllowedModels，不要放宽容差。",
                model.getModelId(), label, drift * 100, oursPerM, upstreamPerM));
    }

    /** 拉线上目录，按 id 索引。这里不带 API key——{@code /models} 是公开端点。 */
    private Map<String, JsonNode> fetchUpstreamCatalog() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(MODELS_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // 网络不通 / 上游 5xx 时 assume 掉而不是失败：这条测试要抓的是价格漂移，
            // 不是给 openrouter.ai 的可用性当探针
            Assumptions.assumeTrue(response.statusCode() == 200,
                    "拉取 OpenRouter 目录失败，HTTP " + response.statusCode() + "，跳过对拍");

            JsonNode data = new ObjectMapper().readTree(response.body()).path("data");
            Assumptions.assumeTrue(data.isArray() && data.size() > 0, "OpenRouter 目录为空，跳过对拍");

            Map<String, JsonNode> byId = new HashMap<>();
            for (JsonNode entry : data) {
                byId.put(entry.path("id").asText(), entry);
            }
            return byId;
        } catch (java.io.IOException e) {
            throw Assumptions.<RuntimeException>abort("拉取 OpenRouter 目录出错，跳过对拍：" + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Assumptions.<RuntimeException>abort("拉取 OpenRouter 目录被中断，跳过对拍");
        }
    }
}
