package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.FileBrief;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * doc_link_evidence / doc_list_evidence（dev-board#112）：AI 面建链的四步 worker 流程
 * （find_text_locations 唯一命中 → set_selection → bookmark_selection → set_selection_hyperlink
 * → get_bookmark_context）与落库契约（createdByKind=ai、sectionPath 取自 context、path → fileId）。
 */
class DocumentEditToolsEvidenceTest {

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long DOC_FILE_ID = 100L;
    private static final String QUOTE = "注册资本为 1000 万元";

    private EditorBridgeService bridge;
    private EvidenceLinkService evidence;
    private ProjectFileRepository repo;
    private DocumentEditTools tools;

    @BeforeEach
    void setUp() {
        bridge = Mockito.mock(EditorBridgeService.class);
        evidence = Mockito.mock(EvidenceLinkService.class);
        repo = Mockito.mock(ProjectFileRepository.class);
        tools = new DocumentEditTools(null, repo, bridge, null, evidence);
        ProjectContextHolder.setProjectId(String.valueOf(PROJECT_ID));
        ProjectContextHolder.setUserId(USER_ID);

        // 底稿文件树：底稿/营业执照.pdf (id=55)
        ProjectFile folder = new ProjectFile();
        folder.setId(1L);
        folder.setName("底稿");
        folder.setIsFolder(true);
        ProjectFile pdf = new ProjectFile();
        pdf.setId(55L);
        pdf.setName("营业执照.pdf");
        pdf.setParentId(1L);
        pdf.setIsFolder(false);
        when(repo.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID)).thenReturn(List.of(folder, pdf));

        // worker 默认按「一切顺利」应答
        stubWorker("{\"matches\":[{\"anchorId\":\"__ai_anchor_1\",\"text\":\"" + QUOTE + "\"}]}");
        when(evidence.create(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(), anyString(), anyList()))
                .thenAnswer(inv -> view(inv.getArgument(3), inv.getArgument(5)));
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    private void stubWorker(String findResult) {
        when(bridge.executeEditorCommand(eq("find_text_locations"), any())).thenReturn(findResult);
        when(bridge.executeEditorCommand(eq("set_selection"), any())).thenReturn("{\"success\":true}");
        when(bridge.executeEditorCommand(eq("bookmark_selection"), any()))
                .thenReturn("{\"success\":true,\"text\":\"" + QUOTE + "\"}");
        when(bridge.executeEditorCommand(eq("set_selection_hyperlink"), any())).thenReturn("{\"success\":true}");
        when(bridge.executeEditorCommand(eq("get_bookmark_context"), any()))
                .thenReturn("{\"success\":true,\"exists\":true,\"text\":\"" + QUOTE + "\",\"sectionPath\":\"一/（二）\","
                        + "\"sectionTitle\":\"（二）注册资本\",\"paragraphIndex\":5}");
    }

    private static LinkView view(String linkKey, String sectionPath) {
        LocalDateTime now = LocalDateTime.now();
        TargetView t = new TargetView(9L, 55L, new FileBrief(55L, "营业执照.pdf", "pdf", 1L, false),
                null, "supports", "written_review", null, null);
        return new LinkView(1L, linkKey, DOC_FILE_ID, QUOTE, "hash", sectionPath, "（二）注册资本",
                "unverified", "ai", now, now, List.of(t));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(ArgumentCaptor<Map<String, Object>> captor) {
        return captor.getValue();
    }

    @Test
    @DisplayName("按 anchorQuote 唯一命中：四步 worker 流程 + create(createdByKind=ai, sectionPath 来自 context)")
    void linksByUniqueQuote() {
        String out = tools.doc_link_evidence(DOC_FILE_ID, QUOTE, null,
                "[{\"fileId\":55,\"locator\":{\"type\":\"pdf\",\"page\":2}}]", null, null, null);

        ArgumentCaptor<Map<String, Object>> bm = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("set_selection"), argThatHas("anchor", "__ai_anchor_1"));
        verify(bridge).executeEditorCommand(eq("bookmark_selection"), bm.capture());
        String linkKey = String.valueOf(paramsOf(bm).get("name"));
        assertTrue(linkKey.matches("EVID_[A-Z0-9]{26}"), "书签名 = EVID_<ULID>，实际: " + linkKey);

        ArgumentCaptor<Map<String, Object>> hl = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("set_selection_hyperlink"), hl.capture());
        String expectedInner = "checkba://filelink?k=" + linkKey + "&projectId=" + PROJECT_ID;
        assertEquals("https://checkba-internal.local/open?u=" + java.net.URLEncoder.encode(expectedInner, java.nio.charset.StandardCharsets.UTF_8),
                paramsOf(hl).get("url"), "URL 与前端 buildFileLinkUrl 同形");
        verify(bridge).executeEditorCommand(eq("get_bookmark_context"), argThatHas("name", linkKey));

        ArgumentCaptor<List<TargetInput>> targets = ArgumentCaptor.forClass(List.class);
        verify(evidence).create(eq(USER_ID), eq(PROJECT_ID), eq(DOC_FILE_ID), eq(linkKey), eq(QUOTE),
                eq("一/（二）"), eq("（二）注册资本"), eq("ai"), targets.capture());
        TargetInput t = targets.getValue().get(0);
        assertEquals(55L, t.fileId());
        assertTrue(t.locatorJson().contains("\"page\":2"), "locator 原样透传为 JSON 串");
        assertEquals("supports", t.relation());

        assertTrue(out.contains(linkKey) && out.contains("unverified"), "返回含 linkKey 与 status: " + out);
        assertTrue(out.contains("一/（二）"), "返回含 sectionPath: " + out);
    }

    @Test
    @DisplayName("anchorQuote 0 命中：返回明确错误，不建书签、不落库")
    void zeroMatchesRefuses() {
        stubWorker("{\"matches\":[]}");
        String out = tools.doc_link_evidence(DOC_FILE_ID, "文档里没有这句", null, "[{\"fileId\":55}]", null, null, null);
        assertTrue(out.startsWith("Error:") && out.contains("0"), out);
        verify(bridge, never()).executeEditorCommand(eq("bookmark_selection"), any());
        verify(evidence, never()).create(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("anchorQuote 多处命中：返回明确错误并提示给更长片段或 anchorId，不建链")
    void multipleMatchesRefuses() {
        stubWorker("{\"matches\":[{\"anchorId\":\"a1\",\"text\":\"甲方\"},{\"anchorId\":\"a2\",\"text\":\"甲方\"}]}");
        String out = tools.doc_link_evidence(DOC_FILE_ID, "甲方", null, "[{\"fileId\":55}]", null, null, null);
        assertTrue(out.startsWith("Error:") && out.contains("2") && out.contains("anchorId"), out);
        verify(bridge, never()).executeEditorCommand(eq("set_selection"), any());
        verify(evidence, never()).create(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("给了 anchorId 就不再查找；path 经文件树路径索引解析成 fileId")
    void anchorIdSkipsSearchAndPathResolves() {
        String out = tools.doc_link_evidence(DOC_FILE_ID, null, "__ai_anchor_9",
                "[{\"path\":\"底稿/营业执照.pdf\",\"method\":\"web_check\"}]", "written_review", "partial", "备注");
        verify(bridge, never()).executeEditorCommand(eq("find_text_locations"), any());
        verify(bridge).executeEditorCommand(eq("set_selection"), argThatHas("anchor", "__ai_anchor_9"));

        ArgumentCaptor<List<TargetInput>> targets = ArgumentCaptor.forClass(List.class);
        verify(evidence).create(eq(USER_ID), eq(PROJECT_ID), eq(DOC_FILE_ID), anyString(), eq(QUOTE),
                eq("一/（二）"), eq("（二）注册资本"), eq("ai"), targets.capture());
        TargetInput t = targets.getValue().get(0);
        assertEquals(55L, t.fileId(), "path → fileId");
        assertEquals("web_check", t.method(), "单条 target 的 method 优先于默认值");
        assertEquals("partial", t.relation(), "relation 缺省取工具级默认");
        assertEquals("备注", t.note());
        assertFalse(out.startsWith("Error:"), out);
    }

    @Test
    @DisplayName("path 解析不到：报错点名该路径，不动编辑器")
    void unknownPathRefuses() {
        String out = tools.doc_link_evidence(DOC_FILE_ID, QUOTE, null, "[{\"path\":\"底稿/不存在.pdf\"}]", null, null, null);
        assertTrue(out.startsWith("Error:") && out.contains("底稿/不存在.pdf"), out);
        verify(bridge, never()).executeEditorCommand(anyString(), any());
        verify(evidence, never()).create(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("worker 书签失败（如选区为空）：不落库，把 worker 的错误原样带回")
    void bookmarkFailureDoesNotPersist() {
        when(bridge.executeEditorCommand(eq("bookmark_selection"), any()))
                .thenReturn("{\"success\":false,\"error\":\"selection is empty\"}");
        String out = tools.doc_link_evidence(DOC_FILE_ID, QUOTE, null, "[{\"fileId\":55}]", null, null, null);
        assertTrue(out.startsWith("Error:") && out.contains("selection is empty"), out);
        verify(bridge, never()).executeEditorCommand(eq("set_selection_hyperlink"), any());
        verify(evidence, never()).create(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺 docFileId / targetsJson 为空：直接报错")
    void missingArgsRefuse() {
        assertTrue(tools.doc_link_evidence(null, QUOTE, null, "[{\"fileId\":55}]", null, null, null).startsWith("Error:"));
        assertTrue(tools.doc_link_evidence(DOC_FILE_ID, QUOTE, null, "[]", null, null, null).startsWith("Error:"));
        assertTrue(tools.doc_link_evidence(DOC_FILE_ID, null, null, "[{\"fileId\":55}]", null, null, null).startsWith("Error:"));
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    @Test
    @DisplayName("doc_list_evidence：按 docFileId 列精简视图；fileId 反查优先；两者都缺报错")
    void listEvidence() {
        when(evidence.listByDoc(eq(USER_ID), eq(PROJECT_ID), eq(DOC_FILE_ID), isNull(), eq("一/")))
                .thenReturn(List.of(view("EVID_X", "一/（二）")));
        String out = tools.doc_list_evidence(DOC_FILE_ID, null, "一/", null);
        assertTrue(out.contains("EVID_X") && out.contains("营业执照.pdf") && out.contains("\"targetId\":9"), out);
        assertTrue(out.contains("一/（二）") && out.contains("unverified"), out);

        when(evidence.listByFile(USER_ID, PROJECT_ID, 55L)).thenReturn(List.of(view("EVID_Y", "二")));
        String byFile = tools.doc_list_evidence(DOC_FILE_ID, 55L, null, null);
        assertTrue(byFile.contains("EVID_Y"), byFile);
        verify(evidence, never()).listByDoc(anyLong(), anyLong(), anyLong(), any(), isNull());

        assertTrue(tools.doc_list_evidence(null, null, null, null).startsWith("Error:"));
    }

    private static Map<String, Object> argThatHas(String key, Object value) {
        return Mockito.argThat(m -> m != null && value.equals(m.get(key)));
    }
}
