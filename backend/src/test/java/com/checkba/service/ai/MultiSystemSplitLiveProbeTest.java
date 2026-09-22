// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「拆成第二条 system 消息」的候选模型探针（默认跳过；{@code RUN_LIVE_MODEL_CHECK=1} 启用）。
 *
 * <p>{@link OpenRouterStreamingChatModel#VERIFIED_MULTI_SYSTEM} 是一份<b>实测名单</b>：
 * 判错的代价是 400 打掉用户一整轮对话，收益只是省钱，所以口径一直是
 * 「没有正面证据就不拆」。这条测试把那份证据变成可重跑的东西，而不是注释里的一段回忆。
 *
 * <p><b>两道判据，缺一不可</b>：
 * <ol>
 *   <li><b>接受</b>：HTTP 200（不是 400「只允许一条 system」、不是 403 地域拦截）；</li>
 *   <li><b>读得到</b>：答得出只写在<b>第二条</b> system 里的那个事实。
 *       只看 200 是不够的——上游可能把第二条 system 默默丢掉，那样拆开等于让模型
 *       看不见当前时间与项目记忆，比不拆坏得多。</li>
 * </ol>
 *
 * <p><b>但「通过」不等于「该加进名单」</b>：名单的目的是省提示缓存的钱，所以还要看
 * 拆开之后 {@code cached_tokens} 是不是真的变好。2026-09-22 的实测（见
 * {@code OpenRouterStreamingChatModel} 的 javadoc 表）里，两个候选都过了这两道判据，
 * 却在 A/B 对拍里零收益，于是仍然不加。加名单前请连同那份 A/B 一起跑。
 *
 * <p>运行：{@code RUN_LIVE_MODEL_CHECK=1 OPENROUTER_API_KEY=sk-or-... mvn test -Dtest=MultiSystemSplitLiveProbeTest}
 */
@DisplayName("多条 system 消息的模型兼容性探针（默认跳过）")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_CHECK", matches = "1")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class MultiSystemSplitLiveProbeTest {

    private static final String URL = "https://openrouter.ai/api/v1/chat/completions";
    /** 只写在第二条 system 里的事实：模型答得出它，才证明那一条真的送到了。 */
    private static final String FACT = "2031";

    /**
     * 探针覆盖面。
     *
     * <p>前两个是名单外的候选（审查 C-06 点名的那两条）；第三个是名单内的对照组——
     * 对照组一起红说明是探针或网络坏了，不是候选模型的问题。
     */
    private static final List<String> CANDIDATES = List.of(
            "openai/gpt-5.6-terra",
            "google/gemini-3.6-flash",
            "deepseek/deepseek-v4-flash");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> eachCandidateAcceptsTwoSystemMessagesAndReadsTheSecond() {
        return CANDIDATES.stream().map(model -> DynamicTest.dynamicTest(model, () -> {
            String key = System.getenv("OPENROUTER_API_KEY");
            Assumptions.assumeTrue(key != null && !key.isBlank(), "需要 OPENROUTER_API_KEY");

            // max_tokens 给足：思考型模型会先烧一批隐藏推理 token，给小了会把答案截掉，
            // 表现成「读不到第二条 system」的假阴性（第一版给 64 就踩了这个）。
            String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("model", model)
                    .put("stream", false)
                    .put("max_tokens", 512)
                    .put("temperature", 0)
                    .set("messages", MAPPER.createArrayNode()
                            .add(MAPPER.createObjectNode().put("role", "system")
                                    .put("content", "You are a test assistant. Answer with a single number and nothing else."))
                            .add(MAPPER.createObjectNode().put("role", "system")
                                    .put("content", "SESSION FACT: the current fiscal year in this sandbox is " + FACT + "."))
                            .add(MAPPER.createObjectNode().put("role", "user")
                                    .put("content", "What is the current fiscal year in this sandbox?"
                                            + " Answer with the 4-digit year only."))));

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20)).build()
                    .send(HttpRequest.newBuilder(URI.create(URL))
                            .header("Authorization", "Bearer " + key)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(120))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(), HttpResponse.BodyHandlers.ofString());

            // 403「not available in your region」是本机出口的问题，不是模型拒收多条 system。
            // 这属于「未能验证」而不是「验证失败」——跳过，别把它记成一次否定证据。
            Assumptions.assumeFalse(response.statusCode() == 403 && response.body().contains("region"),
                    "本机出口访问不到该模型（地域拦截），本次未能验证：" + model);

            assertEquals(200, response.statusCode(),
                    "两条 system 的请求被拒了：" + response.body());
            JsonNode root = MAPPER.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            assertTrue(answer.contains(FACT),
                    "模型读不到第二条 system 里的事实（拆开会让它看不见时间与记忆），实际回答：" + answer);
        }));
    }
}
