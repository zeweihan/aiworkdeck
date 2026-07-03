package com.checkba.service.ai.eval;

import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 LLM 冒烟评测（默认跳过；设置 OPENROUTER_API_KEY 环境变量后启用）。
 *
 * 只跑标注了 "smoke" 的最关键用例（约 3 个）：把真实的工具规格
 * （来自生产工具类）+ 精简系统提示词发给 OpenRouter 上的真实模型，
 * 断言模型的首个工具选择落在期望集合内（闲聊用例断言不调工具）。
 *
 * 可选环境变量：
 * - OPENROUTER_BASE_URL   默认 https://openrouter.ai/api/v1
 * - AI_EVAL_SMOKE_MODEL   默认 google/gemini-2.5-flash
 *
 * 运行：OPENROUTER_API_KEY=sk-or-... mvn test -Dtest=RealLlmSmokeTest
 */
@DisplayName("AI 编排器冒烟评测（真实 LLM，默认跳过）")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class RealLlmSmokeTest {

    @TestFactory
    Stream<DynamicTest> smokeCases() {
        List<EvalCase> smokeCases = EvalCase.loadAll().stream()
                .filter(c -> c.smoke != null)
                .toList();
        assertFalse(smokeCases.isEmpty(), "没有任何用例标注 smoke 字段");

        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENROUTER_API_KEY"))
                .baseUrl(envOr("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
                .modelName(envOr("AI_EVAL_SMOKE_MODEL", "google/gemini-2.5-flash"))
                .temperature(0.0)
                .timeout(Duration.ofSeconds(120))
                .build();

        ToolRegistry registry = new RecordingToolRegistry(RealToolBeans.instantiateAll(), new PluginService());
        registry.init();
        List<ToolSpecification> specs = registry.getAllSpecifications();
        String systemPrompt = loadSmokePrompt();

        return smokeCases.stream().map(c ->
                DynamicTest.dynamicTest("[smoke] " + c.id + " — " + c.title,
                        () -> assertSmoke(model, specs, systemPrompt, c)));
    }

    private void assertSmoke(ChatLanguageModel model, List<ToolSpecification> specs,
                             String systemPrompt, EvalCase c) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(c.userInput));
        Response<AiMessage> response = model.generate(messages, specs);
        AiMessage message = response.content();

        Set<String> allowed = new HashSet<>();
        for (String name : c.smoke.anyOfFirstTools) {
            allowed.add(ToolRegistry.TOOL_NAME_ALIASES.getOrDefault(name, name));
        }

        if (allowed.isEmpty()) {
            // 闲聊用例：不应发起任何工具调用
            assertFalse(message.hasToolExecutionRequests(),
                    "闲聊用例不应调用工具，实际: " + describe(message));
            String text = message.text() == null ? "" : message.text();
            assertFalse(text.contains("<tool_code>"),
                    "闲聊用例不应输出 XML 工具调用，实际文本:\n" + text);
            return;
        }

        if (message.hasToolExecutionRequests()) {
            String firstTool = message.toolExecutionRequests().get(0).name();
            String resolved = ToolRegistry.TOOL_NAME_ALIASES.getOrDefault(firstTool, firstTool);
            assertTrue(allowed.contains(resolved),
                    "首个工具应属于 " + allowed + "，实际: " + describe(message));
        } else {
            // 部分模型会退化到 XML 协议：文本里应出现期望工具名之一
            String text = message.text() == null ? "" : message.text();
            assertTrue(allowed.stream().anyMatch(text::contains),
                    "模型既未发起原生工具调用，文本中也没有期望工具 " + allowed + "。实际文本:\n" + text);
        }
    }

    private String describe(AiMessage message) {
        if (message.hasToolExecutionRequests()) {
            return message.toolExecutionRequests().stream()
                    .map(ToolExecutionRequest::name)
                    .toList()
                    .toString();
        }
        return "text: " + message.text();
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String loadSmokePrompt() {
        Path path = EvalCase.casesDir().getParent().resolve("smoke-system-prompt.txt");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
