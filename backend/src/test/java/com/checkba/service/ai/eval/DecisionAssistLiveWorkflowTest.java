// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiModelProperties;
import com.checkba.service.ai.*;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.tools.*;
import com.checkba.storage.ProjectStorageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit paid opt-in. Real Jev policy + main model + production Java file/catalog execution, synthetic data only. */
@EnabledIfEnvironmentVariable(named = "RUN_JEV_WORKFLOW_LIVE", matches = "1")
class DecisionAssistLiveWorkflowTest {
    @TempDir Path project;

    @Test void compareOriginalAndOptedInReadWithActualJavaTools() throws Exception {
        String key = System.getenv("OPENROUTER_API_KEY");
        assertNotNull(key, "Explicit test credential is required");
        Files.writeString(project.resolve("amounts.txt"), "合成测试材料。第一笔付款577元，第二笔付款423元。均已到账。\n");
        var storage = mock(ProjectStorageResolver.class);
        when(storage.projectRoot(42L)).thenReturn(project);
        var extractor = new FileContentExtractorService(null, new AiContextProperties());
        var fileTools = new FileTools(null, null, null, extractor, storage, null, null, null, null);
        var beans = new ArrayList<>(RealToolBeans.instantiateAll());
        beans.removeIf(bean -> bean instanceof FileTools);
        beans.add(fileTools);
        var registry = new ToolRegistry(beans, new PluginService(), new ClientCapabilityService());
        registry.init();
        var candidates = registry.getAllSpecifications("synthetic-live", null);
        var disclosure = new ToolDisclosurePolicy(false);
        var factory = mock(ChatModelFactory.class);
        when(factory.decisionCredentials(any(), eq(AiModelProperties.Provider.OPENROUTER)))
                .thenReturn(new ChatModelFactory.DecisionCredentials(key, "https://openrouter.ai/api/v1", false));
        var receipts = new ArrayList<Map<String, Object>>();
        var usage = mock(TokenUsageService.class);
        doAnswer(inv -> {
            receipts.add(Map.of("model", inv.getArgument(3), "input", inv.getArgument(4),
                    "output", inv.getArgument(5), "cost", inv.getArgument(6)));
            return null;
        }).when(usage).recordDecisionUsage(any(), any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean());
        var decisions = new DecisionAssistService(factory, usage, new ObjectMapper());
        var selector = new ToolDecisionPolicy(decisions, disclosure);
        var model = OpenAiChatModel.builder().apiKey(key).baseUrl("https://openrouter.ai/api/v1")
                .modelName("deepseek/deepseek-v4-flash").maxTokens(2048).maxRetries(0)
                .temperature(0.0).timeout(Duration.ofSeconds(60)).build();
        String request = "读取磁盘路径 amounts.txt 的付款清单，计算两笔金额合计。它未入库，没有 fileId，请按路径读取。";
        var metrics = new ArrayList<Map<String, Object>>();
        try {
            for (boolean enabled : List.of(false, true)) {
                long started = System.nanoTime();
                var context = new DecisionAssistContext(enabled, 42L, 7L, "synthetic-live", "deepseek/deepseek-v4-flash",
                        AiModelProperties.Provider.OPENROUTER);
                Optional<String> selected = selector.select(context, request, candidates);
                if (enabled) assertTrue(selected.isPresent(), "This live check must exercise actual preselection; a fallback is safe but does not validate the selected path");
                Set<String> expanded = new HashSet<>();
                selected.filter(s -> !s.equals("core")).ifPresent(expanded::add);
                List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from(
                        "仅处理这个合成测试项目。使用原生工具调用，读取已给相对路径使用read_file，读取后简短回答。"), UserMessage.from(request)));
                boolean read = false, finished = false;
                int promptTokens = 0, outputTokens = 0, rounds = 0;
                for (; rounds < 3; rounds++) {
                    List<ToolSpecification> offered = selected.isPresent()
                            ? candidates.stream().filter(s -> disclosure.narrow(candidates, expanded).contains(s)
                                    || Set.of("memory_list", "memory_read", "memory_search", "memory_write", "memory_edit", "memory_delete").contains(s.name())).toList()
                            : candidates.stream().filter(s -> !s.name().equals("list_tools")).toList();
                    var response = model.generate(messages, offered);
                    if (response.tokenUsage() != null) {
                        promptTokens += response.tokenUsage().inputTokenCount();
                        outputTokens += response.tokenUsage().outputTokenCount();
                    }
                    AiMessage reply = response.content();
                    messages.add(reply);
                    if (!reply.hasToolExecutionRequests()) {
                        assertTrue(read, "A final answer without actual file execution is not a pass");
                        assertNotNull(reply.text());
                        assertTrue(reply.text().replace(",", "").contains("1000"), "Known synthetic total must be correct");
                        finished = true; rounds++; break;
                    }
                    for (var call : reply.toolExecutionRequests()) {
                        assertTrue(Set.of("read_file", "list_tools").contains(call.name()), "No other tool may execute in this live fixture");
                        var args = new ObjectMapper().readTree(call.arguments());
                        if (call.name().equals("read_file")) assertEquals("amounts.txt", args.path("filePath").asText());
                        var result = registry.execute(call.name(), call.arguments(),
                                new ToolContext(42L, "synthetic-live", 7L, "deepseek/deepseek-v4-flash", candidates));
                        assertTrue(result.success());
                        if (call.name().equals("read_file")) {
                            assertTrue(result.output().contains("577"));
                            assertTrue(result.output().contains("423"));
                            read = true;
                        } else expanded.addAll(disclosure.parseCategories(args.path("category").asText()));
                        messages.add(ToolExecutionResultMessage.from(call, result.output()));
                    }
                }
                assertTrue(finished, "Bounded fixture did not finish; do not hide an incomplete result");
                metrics.add(Map.of("enabled", enabled, "selection", selected.orElse("fallback"),
                        "millis", Duration.ofNanos(System.nanoTime() - started).toMillis(), "rounds", rounds,
                        "main_input_tokens", promptTokens, "main_output_tokens", outputTokens, "actual_java_read", read));
            }
            String output = System.getenv("JEV_LIVE_OUTPUT");
            if (output != null) Files.writeString(Path.of(output), new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("runs", metrics, "jev_receipts", receipts)));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(decisions, "close");
        }
    }
}
