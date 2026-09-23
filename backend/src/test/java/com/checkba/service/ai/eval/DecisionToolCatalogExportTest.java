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
        var specs = registry.getAllSpecifications("synthetic-decision-eval", "docx");
        var policy = new ToolDisclosurePolicy(true);
        Map<String, String> categories = new LinkedHashMap<>();
        for (var spec : specs) categories.put(spec.name(), policy.categoryOf(spec.name()));
        var mapper = new ObjectMapper();
        Files.writeString(Path.of(System.getenv("JEV_TOOL_CATALOG_OUTPUT")), mapper.writeValueAsString(Map.of(
                "tools", mapper.readTree(Json.toJson(InternalOpenAiHelper.toTools(specs, false))),
                "categoryByTool", categories, "core", policy.coreToolNames())));
    }
}
