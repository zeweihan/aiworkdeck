package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.util.style.StyleProfile;
import com.checkba.util.style.StyleProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 样式画像工具面（dev-board#111）的参数映射：doc_* 的命名参数 → worker action 的 params。
 * 新参一律加在签名末尾（旧会话按位置回放不错位），这里顺带锁住既有参数的顺序没被挪动。
 */
class DocumentEditToolsStyleTest {

    private EditorBridgeService bridge;
    private StyleProfileResolver resolver;
    private DocumentEditTools tools;

    @BeforeEach
    void setUp() {
        bridge = Mockito.mock(EditorBridgeService.class);
        resolver = Mockito.mock(StyleProfileResolver.class);
        tools = new DocumentEditTools(null, null, bridge, null, null, null, null, resolver);
        when(bridge.executeEditorCommand(any(), any())).thenReturn("{\"success\":true}");
        ProjectContextHolder.setProjectId("7");
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(String action) {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq(action), cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("doc_apply_style_profile：先 set_style_profile（项目画像）再 apply_style_profile(scope)")
    void applyStyleProfileSendsProfileThenApplies() {
        StyleProfile custom = StyleProfiles.houseDefault().merge(StyleProfiles.parse(
                "{\"schemaVersion\":1,\"body\":{\"font\":{\"western\":\"Times New Roman\"}}}"));
        when(resolver.resolve(7L, null)).thenReturn(custom);

        String out = tools.doc_apply_style_profile("selection");

        assertFalse(out.startsWith("Error"), out);
        InOrder order = Mockito.inOrder(bridge);
        order.verify(bridge).executeEditorCommand(eq("set_style_profile"), any());
        order.verify(bridge).executeEditorCommand(eq("apply_style_profile"), any());
        Map<String, Object> set = paramsOf("set_style_profile");
        Map<String, Object> profile = (Map<String, Object>) set.get("profile");
        assertEquals(1, profile.get("schemaVersion"));
        assertEquals("Times New Roman", ((Map<String, Object>) ((Map<String, Object>) profile.get("body")).get("font")).get("western"));
        assertEquals("楷体_GB2312", ((Map<String, Object>) ((Map<String, Object>) profile.get("body")).get("font")).get("eastAsia"),
                "项目画像要 merge 在 house-default 之上，缺省叶子由 HOUSE 补齐");
        assertEquals("selection", paramsOf("apply_style_profile").get("scope"));
    }

    @Test
    @DisplayName("doc_apply_style_profile：没有解析器时按 house-default 下发，scope 缺省 document")
    void applyStyleProfileFallsBackToHouseDefault() {
        DocumentEditTools bare = new DocumentEditTools(null, null, bridge, null, null, null, null, null);
        bare.doc_apply_style_profile(null);
        Map<String, Object> profile = (Map<String, Object>) paramsOf("set_style_profile").get("profile");
        assertEquals("律所标准格式（HOUSE）", profile.get("name"));
        assertEquals("document", paramsOf("apply_style_profile").get("scope"));
    }

    @Test
    @DisplayName("doc_apply_style_profile：画像下发失败就不再 apply")
    void applyStyleProfileStopsWhenSetFails() {
        when(resolver.resolve(7L, null)).thenReturn(StyleProfiles.houseDefault());
        when(bridge.executeEditorCommand(eq("set_style_profile"), any())).thenReturn("{\"error\":\"编辑器未就绪\"}");
        String out = tools.doc_apply_style_profile("document");
        assertTrue(out.startsWith("Error"), out);
        verify(bridge, never()).executeEditorCommand(eq("apply_style_profile"), any());
    }

    @Test
    @DisplayName("doc_insert_toc / doc_set_page_setup 参数映射")
    void tocAndPageSetupMapping() {
        tools.doc_insert_toc(2, "目  录", "start");
        Map<String, Object> toc = paramsOf("insert_toc");
        assertEquals(2, toc.get("levels"));
        assertEquals("目  录", toc.get("title"));
        assertEquals("start", toc.get("position"));

        tools.doc_set_page_setup(210.0, 297.0, "landscape", 25.4, null, 31.7, null);
        Map<String, Object> pg = paramsOf("set_page_setup");
        assertEquals(210.0, pg.get("width"));
        assertEquals(297.0, pg.get("height"));
        assertEquals("landscape", pg.get("orientation"));
        Map<String, Object> margins = (Map<String, Object>) pg.get("margins");
        assertEquals(25.4, margins.get("top"));
        assertEquals(31.7, margins.get("left"));
        assertFalse(margins.containsKey("bottom"));

        assertTrue(tools.doc_set_page_setup(null, null, null, null, null, null, null).startsWith("Error"),
                "没给任何参数要在 Java 侧拦下，不白发一条空命令");
    }

    @Test
    @DisplayName("doc_edit_header_footer：pageNumberPattern 可替代 text；fontName/fontSize 透传")
    void headerFooterPattern() {
        assertTrue(tools.doc_edit_header_footer("footer", null, null, null, null, null).startsWith("Error"),
                "既无 text 也无 pageNumberPattern 要拦下");
        tools.doc_edit_header_footer("footer", null, "center", "第 {PAGE} 页 共 {NUMPAGES} 页", "宋体", 9.0);
        Map<String, Object> p = paramsOf("edit_header_footer");
        assertEquals("footer", p.get("target"));
        assertNull(p.get("text"));
        assertEquals("第 {PAGE} 页 共 {NUMPAGES} 页", p.get("pageNumberPattern"));
        assertEquals("宋体", p.get("fontName"));
        assertEquals(9.0, p.get("fontSize"));
        assertEquals("center", p.get("align"));
    }

    @Test
    @DisplayName("doc_format_table 新参与 doc_format_selection.fontNameAsian 透传，旧参位置不变")
    void formatTableAndSelectionNewParams() {
        tools.doc_format_table(null, null, null, null, null, null, null, null, 0,
                "#FF0000", "double", 1.5, 0.5, "#DDDDDD", true, "3,5");
        Map<String, Object> ft = paramsOf("format_table");
        assertEquals(0, ft.get("tableIndex"));
        assertEquals("#FF0000", ft.get("borderColor"));
        assertEquals("double", ft.get("borderStyle"));
        assertEquals(1.5, ft.get("outsideBorderWidthPt"));
        assertEquals(0.5, ft.get("insideBorderWidthPt"));
        assertEquals("#DDDDDD", ft.get("headerFill"));
        assertEquals(Boolean.TRUE, ft.get("repeatHeader"));
        assertEquals("3,5", ft.get("columnWidthsCm"));
        assertFalse(ft.containsKey("borderWidthPt"));

        tools.doc_format_selection(null, null, null, null, null, null, null, "Arial", "楷体_GB2312");
        Map<String, Object> fs = paramsOf("format_selection");
        assertEquals("Arial", fs.get("fontName"));
        assertEquals("楷体_GB2312", fs.get("fontNameAsian"));
    }

    @Test
    @DisplayName("nonHouseProfileMap：house-default 不下发，改过任一叶子才下发")
    void nonHouseProfileMapOnlyForCustomProfiles() {
        assertNull(EditorBridgeService.nonHouseProfileMap(StyleProfiles.houseDefault()));
        assertNull(EditorBridgeService.nonHouseProfileMap(null));
        StyleProfile custom = StyleProfiles.houseDefault().merge(StyleProfiles.parse(
                "{\"body\":{\"firstLineIndent\":{\"value\":0,\"unit\":\"pt\"}}}"));
        Map<String, Object> m = EditorBridgeService.nonHouseProfileMap(custom);
        assertTrue(m != null && m.containsKey("headings"));
    }

    @Test
    @DisplayName("doc_start_stream 的 doc_open_file_sync 只在项目画像非 house-default 时带 styleProfile")
    void openSyncCarriesProfileOnlyWhenCustom() {
        // 解析器为 null → house-default → 不带
        com.checkba.service.ProjectFileService pfs = Mockito.mock(com.checkba.service.ProjectFileService.class);
        com.checkba.model.entity.ProjectFile f = new com.checkba.model.entity.ProjectFile();
        f.setId(100L); f.setName("x.docx"); f.setFileType("docx"); f.setProjectId(7L);
        when(pfs.getFile(100L)).thenReturn(f);
        when(bridge.executeEditorCommand(eq("doc_open_file_sync"), any())).thenReturn("{\"status\":\"ready\"}");
        when(bridge.getCurrentConversationId()).thenReturn("conv");
        DocumentEditTools bare = new DocumentEditTools(pfs, null, bridge, null, null, null, null, null);
        bare.doc_start_stream(100L, null, null);
        assertFalse(paramsOf("doc_open_file_sync").containsKey("styleProfile"));

        Mockito.clearInvocations(bridge);
        when(resolver.resolve(7L, null)).thenReturn(StyleProfiles.houseDefault().merge(StyleProfiles.parse(
                "{\"body\":{\"font\":{\"western\":\"Times New Roman\"}}}")));
        DocumentEditTools withResolver = new DocumentEditTools(pfs, null, bridge, null, null, null, null, resolver);
        withResolver.doc_start_stream(100L, null, null);
        assertTrue(paramsOf("doc_open_file_sync").containsKey("styleProfile"));
    }
}
