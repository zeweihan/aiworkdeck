// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.ref.RefEntry;
import com.checkba.service.ai.ref.RefQuery;
import com.checkba.service.ai.ref.RefSource;
import com.checkba.service.ai.ref.ReferenceSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ref_* 工具：参数解析、服务端上下文注入（userId/projectId/conversationId 以服务端为准）、
 * 以及经 ToolRegistry 分发时的会话可见性（只对 OFFICE 会话）。
 */
class ReferenceToolsTest {

    /** 记录每次调用拿到的 RefQuery 与参数的假来源。 */
    static final class RecordingSource implements RefSource {
        final String scheme;
        final List<String> calls = new ArrayList<>();
        RefQuery lastQuery;
        Map<String, Object> lastArgs;

        RecordingSource(String scheme) { this.scheme = scheme; }

        public String scheme() { return scheme; }

        public List<RefEntry> list(RefQuery q) {
            lastQuery = q;
            calls.add("list");
            return List.of(new RefEntry(scheme + ":1", scheme, "B.docx", "B.docx", "word", null, null));
        }

        public String read(RefQuery q, String body, String locator) {
            lastQuery = q;
            calls.add("read " + body + " " + locator);
            return "正文";
        }

        public String edit(RefQuery q, String body, String command, Map<String, Object> args) {
            lastQuery = q;
            lastArgs = args;
            calls.add("edit " + body + " " + command);
            return "{\"ok\":true}";
        }

        public String open(RefQuery q, String body) {
            lastQuery = q;
            calls.add("open " + body);
            return "已打开";
        }
    }

    private RecordingSource open;
    private RecordingSource desk;
    private ReferenceTools tools;

    @BeforeEach
    void setUp() {
        open = new RecordingSource("open");
        desk = new RecordingSource("desk");
        tools = new ReferenceTools(new ReferenceSourceService(List.of(open, desk)), new ObjectMapper());
    }

    @Test
    void listPassesKeywordAndContext() {
        String out = tools.ref_list("合同", "open", 7L, 11L, "conv-a");
        assertThat(out).contains("open:1").doesNotContain("desk:1");
        assertThat(open.lastQuery).isEqualTo(new RefQuery(7L, 11L, "conv-a", "合同"));
    }

    @Test
    void blankKeywordIsNormalisedToNull() {
        tools.ref_list("  ", null, 7L, 11L, "conv-a");
        assertThat(open.lastQuery.query()).isNull();
    }

    @Test
    void readDispatches() {
        assertThat(tools.ref_read("open:1", "page:3", 7L, 11L, "conv-a")).isEqualTo("正文");
        assertThat(open.calls).contains("read 1 page:3");
    }

    @Test
    void editParsesArgsJsonIntoAMap() {
        String out = tools.ref_edit("open:1", "replace_text",
                "{\"find\":\"甲方\",\"replace\":\"乙方\",\"n\":2}", 7L, 11L, "conv-a");
        assertThat(out).isEqualTo("{\"ok\":true}");
        assertThat(open.lastArgs).containsEntry("find", "甲方").containsEntry("replace", "乙方").containsEntry("n", 2);
        assertThat(open.lastQuery.conversationId()).isEqualTo("conv-a");
    }

    @Test
    void editWithBlankArgsUsesAnEmptyObject() {
        tools.ref_edit("open:1", "accept_all", null, 7L, 11L, "conv-a");
        assertThat(open.lastArgs).isEmpty();
        tools.ref_edit("open:1", "accept_all", "  ", 7L, 11L, "conv-a");
        assertThat(open.lastArgs).isEmpty();
    }

    @Test
    void editRejectsNonObjectArgs() {
        assertThat(tools.ref_edit("open:1", "replace_text", "not json", 7L, 11L, "conv-a"))
                .isEqualTo("错误：args 必须是 JSON 对象");
        assertThat(tools.ref_edit("open:1", "replace_text", "[1,2]", 7L, 11L, "conv-a"))
                .isEqualTo("错误：args 必须是 JSON 对象");
        assertThat(open.calls).noneMatch(c -> c.startsWith("edit"));
    }

    @Test
    void editRequiresACommand() {
        assertThat(tools.ref_edit("open:1", " ", "{}", 7L, 11L, "conv-a")).startsWith("错误：");
        assertThat(open.calls).noneMatch(c -> c.startsWith("edit"));
    }

    @Test
    void openDispatches() {
        assertThat(tools.ref_open("desk:d1:p1:a.docx", 7L, 11L, "conv-a")).isEqualTo("已打开");
        assertThat(desk.calls).contains("open d1:p1:a.docx");
    }

    @Test
    void registryInjectsServerContextAndHidesToolsFromNonOfficeSessions() {
        ClientCapabilityService capabilities = new ClientCapabilityService();
        capabilities.record("conv-office", "office");
        capabilities.record("conv-lowa", "lowa");
        ToolRegistry registry = new ToolRegistry(List.of(tools), new PluginService(), capabilities);
        registry.init();

        List<String> officeNames = registry.getAllSpecifications("conv-office").stream()
                .map(ToolSpecification::name).toList();
        assertThat(officeNames).contains("ref_list", "ref_read", "ref_edit", "ref_open");
        List<String> lowaNames = registry.getAllSpecifications("conv-lowa").stream()
                .map(ToolSpecification::name).toList();
        assertThat(lowaNames).doesNotContain("ref_list", "ref_read", "ref_edit", "ref_open");
        assertThat(registry.execute("ref_read", "{\"ref\":\"open:1\"}",
                new ToolContext(11L, "conv-lowa", 7L, "m")).found()).isFalse();

        // 模型伪造的 userId/projectId/conversationId 一律被服务端上下文覆盖
        ToolRegistry.ToolResult result = registry.execute("ref_read",
                "{\"ref\":\"open:1\",\"locator\":\"page:2\",\"userId\":999,\"projectId\":999,\"conversationId\":\"conv-evil\"}",
                new ToolContext(11L, "conv-office", 7L, "m"));
        assertThat(result.found()).isTrue();
        assertThat(result.output()).isEqualTo("正文");
        assertThat(open.lastQuery).isEqualTo(new RefQuery(7L, 11L, "conv-office", null));
    }
}
