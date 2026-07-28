package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃文档的确定性兜底（分发层拦截）。
 *
 * <p>背景：提示词层面的约束对弱模型不可靠——system prompt 里的 &lt;active_document&gt; 声明
 * （PR#187）和用户消息末位提醒（PR#209）都被无视过，真机日志实证注入后 6 秒仍调
 * doc_list_project_files。这两个 helper 在工具分发层兜底，不依赖模型自觉。
 */
class AgentOrchestratorActiveDocTest {

    // ==== doc_open_file 短路 ====

    @Test
    @DisplayName("打开的就是活跃文档本身 → 短路，省掉一轮前端往返")
    void shortCircuitsOpeningTheAlreadyOpenDocument() {
        String msg = AgentOrchestrator.activeDocOpenShortCircuit(123L, "合作框架协议.docx", "123");

        assertNotNull(msg, "打自己应短路");
        assertTrue(msg.contains("合作框架协议.docx"), "反馈里应点名文档");
        assertTrue(msg.contains("本来就在编辑器中打开着"), "应说明无需打开");
        assertTrue(msg.contains("doc_list_project_files"), "应顺带堵住再去列文件的退路");
    }

    @Test
    @DisplayName("文件名含引号/反斜杠也不产出坏结构——短路走纯文本，与工具自身返回格式一致")
    void shortCircuitSurvivesQuotesInFileName() {
        String msg = AgentOrchestrator.activeDocOpenShortCircuit(
                123L, "他说\"你好\"\\备份.docx", "123");

        assertNotNull(msg);
        assertTrue(msg.contains("他说\"你好\"\\备份.docx"), "文件名应原样呈现，不被转义破坏");
        assertTrue(!msg.trim().startsWith("{"), "不应手拼 JSON——doc_open_file 本身返回纯文本");
    }

    @Test
    @DisplayName("打开的是别的文档 → 不短路，跨文档场景必须照常执行")
    void doesNotShortCircuitOtherDocuments() {
        assertNull(AgentOrchestrator.activeDocOpenShortCircuit(123L, "合作框架协议.docx", "456"),
                "跨文档操作不能被拦截");
    }

    @Test
    @DisplayName("无活跃文档或参数缺失 → 一律不短路")
    void doesNotShortCircuitWithoutActiveDoc() {
        assertNull(AgentOrchestrator.activeDocOpenShortCircuit(null, "x.docx", "123"));
        assertNull(AgentOrchestrator.activeDocOpenShortCircuit(123L, "x.docx", null));
        assertNull(AgentOrchestrator.activeDocOpenShortCircuit(123L, "x.docx", "  "));
    }

    @Test
    @DisplayName("非数字 fileId（临时文件等）不短路，交由工具自己报错")
    void doesNotShortCircuitNonNumericId() {
        assertNull(AgentOrchestrator.activeDocOpenShortCircuit(123L, "x.docx", "tmp_abc"));
    }

    @Test
    @DisplayName("文档名缺失时短路反馈仍可用，不出现《null》或空书名号")
    void shortCircuitToleratesMissingName() {
        for (String noName : new String[]{null, "", "  "}) {
            String msg = AgentOrchestrator.activeDocOpenShortCircuit(123L, noName, "123");

            assertNotNull(msg);
            assertTrue(msg.contains("当前文档"), "无名时应回退为通称");
            assertTrue(!msg.contains("《》") && !msg.contains("null"), "不应漏出 null 或空书名号");
        }
    }

    // ==== doc_list_project_files 结果加钉 ====

    @Test
    @DisplayName("列文件结果尾部钉上活跃文档，让走神的模型下一轮自纠")
    void appendsActiveDocNoticeToListOutput() {
        String out = AgentOrchestrator.appendActiveDocNotice(
                "[{\"id\":1,\"name\":\"a.docx\"}]", 123L, "合作框架协议.docx");

        assertTrue(out.startsWith("[{"), "原始列表必须保留在前");
        assertTrue(out.contains("[系统提醒]"), "应沿用既有系统提醒惯例");
        assertTrue(out.contains("合作框架协议.docx"), "应点名活跃文档");
        assertTrue(out.contains("123"), "应带上 id");
    }

    @Test
    @DisplayName("无活跃文档时列文件结果原样返回")
    void leavesListOutputUntouchedWithoutActiveDoc() {
        String raw = "[{\"id\":1}]";
        org.junit.jupiter.api.Assertions.assertEquals(
                raw, AgentOrchestrator.appendActiveDocNotice(raw, null, "x.docx"));
    }

    @Test
    @DisplayName("列表加钉在文档名缺失时回退通称，不漏出 null")
    void listNoticeToleratesMissingName() {
        String out = AgentOrchestrator.appendActiveDocNotice("[]", 123L, null);

        assertTrue(out.contains("当前文档"), "无名时应回退为通称");
        assertTrue(!out.contains("null"), "不应把 null 漏给模型");
    }

    // ==== 模型中途切文档后的文档名归属 ====

    @Test
    @DisplayName("模型打开别的文档 → 旧文档名必须作废，不能拿旧名配新 id")
    void invalidatesNameWhenModelOpensDifferentDocument() {
        assertNull(AgentOrchestrator.activeDocNameAfterOpen(123L, "合作框架协议.docx", 456L),
                "切文档后旧名必须作废，否则会喂给模型错误信息");
    }

    @Test
    @DisplayName("模型重复打开同一文档 → 文档名保留")
    void keepsNameWhenReopeningSameDocument() {
        org.junit.jupiter.api.Assertions.assertEquals("合作框架协议.docx",
                AgentOrchestrator.activeDocNameAfterOpen(123L, "合作框架协议.docx", 123L));
    }

    @Test
    @DisplayName("此前无活跃文档时打开任意文档 → 名字仍未知")
    void nameStaysUnknownWhenThereWasNoActiveDoc() {
        assertNull(AgentOrchestrator.activeDocNameAfterOpen(null, null, 456L));
    }
}
