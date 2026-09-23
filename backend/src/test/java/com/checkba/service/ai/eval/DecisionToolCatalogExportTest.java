// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolDisclosurePolicy;
import com.checkba.service.ai.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.Json;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;

/** Export production schemas for a bounded synthetic live comparison; no tool execution or user data. */
@EnabledIfEnvironmentVariable(named = "JEV_TOOL_CATALOG_OUTPUT", matches = ".+")
class DecisionToolCatalogExportTest {
    @Test void export() throws Exception {
        var registry = new RecordingToolRegistry(RealToolBeans.instantiateAll(), new PluginService());
        registry.init();
        var specs = registry.getAllSpecifications("synthetic-decision-eval", null);
        var policy = new ToolDisclosurePolicy(true);
        Map<String, String> categories = new LinkedHashMap<>();
        for (var spec : specs) categories.put(spec.name(), policy.categoryOf(spec.name()));
        var mapper = new ObjectMapper();
        String payloadOutput = System.getenv("JEV_POLICY_PAYLOAD_OUTPUT");
        if (payloadOutput != null) {
            var selector = new com.checkba.service.ai.ToolDecisionPolicy(null, policy);
            Map<String, java.util.List<String>> available = org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(selector, "availableTools", specs);
            Map<String, String> criteria = org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(com.checkba.service.ai.ToolDecisionPolicy.class, "criteria", available);
            String instructions = (String) org.springframework.test.util.ReflectionTestUtils
                    .getField(com.checkba.service.ai.ToolDecisionPolicy.class, "INSTRUCTIONS");
            Map<String, Integer> sizes = new LinkedHashMap<>();
            java.util.Set<String> memory = (java.util.Set<String>) org.springframework.test.util.ReflectionTestUtils
                    .getField(com.checkba.service.ai.AgentOrchestrator.class, "MEMORY_TOOLS");
            for (String category : available.keySet()) {
                var narrowed = policy.narrow(specs, "core".equals(category) ? java.util.Set.of() : java.util.Set.of(category));
                var kept = specs.stream().filter(s -> narrowed.contains(s) || memory.contains(s.name())).toList();
                sizes.put(category, Json.toJson(InternalOpenAiHelper.toTools(kept, false)).length());
            }
            Files.writeString(Path.of(payloadOutput), mapper.writeValueAsString(Map.of(
                    "instructions", instructions, "available_tools", available, "criteria", criteria,
                    "full_schema_chars", Json.toJson(InternalOpenAiHelper.toTools(specs, false)).length(),
                    "schema_chars_by_choice", sizes)));
        }
        Files.writeString(Path.of(System.getenv("JEV_TOOL_CATALOG_OUTPUT")), mapper.writeValueAsString(Map.of(
                "tools", mapper.readTree(Json.toJson(InternalOpenAiHelper.toTools(specs, false))),
                "categoryByTool", categories, "core", policy.coreToolNames())));
    }
}
