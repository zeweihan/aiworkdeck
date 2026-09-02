package com.checkba.service.ai.tools;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机没有 Docker 时 run_python 不摆到模型面前（dev-board#396）。
 *
 * <p>病灶：用户让 AI 读项目里的一张 jpg，模型抽不到文字后把 run_python 当成
 * 「另找一条 OCR 路子」，拿到的是 "Cannot run program docker"，于是它自己下结论
 * 「OCR 环境（docker）不可用」并这样告诉用户——而图片一直是能读的。
 */
class PythonToolsDockerGateTest {

    private static PythonTools pythonTools(boolean dockerAvailable) {
        PythonTools tools = new PythonTools(null, null, null, null);
        tools.overrideDockerAvailable(dockerAvailable);
        return tools;
    }

    private static List<String> specNames(PythonTools tools) {
        ToolRegistry registry =
                new ToolRegistry(List.of(tools), new PluginService(), new ClientCapabilityService());
        registry.init();
        return registry.getAllSpecifications().stream().map(ToolSpecification::name).toList();
    }

    @Test
    @DisplayName("没有 Docker：run_python 不在给模型的工具清单里；有 Docker：照常在")
    void runPythonIsGatedOnDocker() {
        assertFalse(specNames(pythonTools(false)).contains("run_python"));
        assertTrue(specNames(pythonTools(true)).contains("run_python"));
    }

    @Test
    @DisplayName("真被调到时说的是「Python 沙箱缺 Docker」，并明确不牵连读文件与 OCR")
    void safetyNetMessageDoesNotImplyOcrIsBroken() {
        String out = pythonTools(false).run_python("print(1)");

        assertTrue(out.startsWith("Error"), "必须判定为失败：" + out);
        assertTrue(out.contains("Docker"), "要说清缺的是什么：" + out);
        assertTrue(out.contains("read_file") && out.contains("extract_file_text"),
                "必须指回真正能读图的工具，否则模型又会自己下「读不了图」的结论：" + out);
        assertTrue(out.contains("OCR"), "要明说 OCR 不受影响：" + out);
    }
}
