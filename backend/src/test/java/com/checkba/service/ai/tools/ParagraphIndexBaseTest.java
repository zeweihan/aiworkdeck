package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import dev.langchain4j.agent.tool.P;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * doc_* 段落号的基数契约：**全族 0 基**，且缺参绝不放行。
 *
 * <p>病灶：编辑器侧一律 0 基——{@code office_thread.js} 的 get_paragraph /
 * modify_paragraph / select_paragraph 都是从 {@code i = 0} 起数、{@code i === idx} 命中，
 * {@code get_document_text} 返回的也是 {@code index: i}（0 起）。
 * 可 {@code doc_get_paragraph} 与 {@code doc_modify_paragraph} 的参数说明写的是
 * 「段落索引，从 1 开始」，而同族的 {@code doc_get_document_text}、
 * {@code doc_select_paragraph} 写的是 0 开始。
 *
 * <p>模型先用 doc_get_document_text 拿到 0 基编号，再照着「从 1 开始」的说明去调
 * doc_modify_paragraph，就会整体差一段——**在修订模式下改错条款**，而用户很可能直接接受。
 * 对法律文书来说这是最不能有的一类错。
 *
 * <p>另一半是缺参：编辑器的 {@code Number(p.index) || 0} 把 null／非数字静默当成第 0 段，
 * 于是「没给段落号」变成「改第一段」，同样无人察觉。
 */
class ParagraphIndexBaseTest {

    private static DocumentEditTools toolsWithBridge(EditorBridgeService bridge) {
        return new DocumentEditTools(null, null, bridge, null, null, null);
    }

    /** 收集所有 @P 说明里提到「段落」的参数 */
    private static List<String> paragraphParamDocs() {
        List<String> docs = new ArrayList<>();
        for (Method m : DocumentEditTools.class.getDeclaredMethods()) {
            for (Parameter param : m.getParameters()) {
                P p = param.getAnnotation(P.class);
                if (p == null) continue;
                // 只看「段落号/段落索引」这类整数定位参数：段落数（计数）、段落文本（String）、
                // doc_goto 的定位类型都不是基数问题
                boolean isIndexParam = (param.getType() == Integer.class || param.getType() == int.class)
                        && (p.value().contains("段落号") || p.value().contains("段落索引"));
                if (isIndexParam) {
                    docs.add(m.getName() + " / " + param.getName() + " -> " + p.value());
                }
            }
        }
        return docs;
    }

    @Test
    @DisplayName("没有任何段落号参数敢自称「从 1 开始」——编辑器侧一律 0 基")
    void noParagraphParameterClaimsOneBasedIndexing() {
        List<String> docs = paragraphParamDocs();
        assertFalse(docs.isEmpty(), "用例前提：应当能反射到段落号参数");

        List<String> liars = docs.stream()
                .filter(d -> d.contains("从 1 开始") || d.contains("1 开始") && !d.contains("0 开始"))
                .toList();
        assertTrue(liars.isEmpty(),
                "编辑器 office_thread.js 的 get_paragraph/modify_paragraph/select_paragraph 都是 0 基，"
                        + "get_document_text 返回的 index 也是 0 起；说明写成 1 基会让模型整体差一段、"
                        + "在修订模式下改错条款。违规参数：" + liars);
    }

    @Test
    @DisplayName("每个段落号参数都要点明 0 基，模型才不用猜")
    void everyParagraphParameterStatesTheBaseExplicitly() {
        for (String doc : paragraphParamDocs()) {
            assertTrue(doc.contains("0 开始"), "段落号说明必须写明 0 基，实际是：" + doc);
        }
    }

    @Test
    @DisplayName("缺段落号：返回可行动错误，且绝不下发到编辑器（否则静默改第 0 段）")
    void missingParagraphIndexIsRejectedBeforeDispatch() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        DocumentEditTools tools = toolsWithBridge(bridge);

        String got = tools.doc_get_paragraph(null);
        String modified = tools.doc_modify_paragraph(null, "新条款");

        assertTrue(got.startsWith("Error"), "实际是：" + got);
        assertTrue(got.contains("0-based"), "要告诉模型基数，实际是：" + got);
        assertTrue(modified.startsWith("Error"), "实际是：" + modified);

        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    @Test
    @DisplayName("负段落号同样拦下")
    void negativeParagraphIndexIsRejected() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        DocumentEditTools tools = toolsWithBridge(bridge);

        assertTrue(tools.doc_get_paragraph(-1).startsWith("Error"));
        assertTrue(tools.doc_modify_paragraph(-3, "x").startsWith("Error"));
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    @Test
    @DisplayName("缺 newText 不许下发：编辑器会把它当成空串，等于清空该段")
    void missingNewTextIsRejectedBeforeDispatch() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        DocumentEditTools tools = toolsWithBridge(bridge);

        String out = tools.doc_modify_paragraph(0, null);

        assertTrue(out.startsWith("Error"), "实际是：" + out);
        verify(bridge, never()).executeEditorCommand(anyString(), any());
    }

    @Test
    @DisplayName("0 是合法段落号（首段），必须放行")
    void zeroIsAValidParagraphIndex() {
        EditorBridgeService bridge = Mockito.mock(EditorBridgeService.class);
        Mockito.when(bridge.executeEditorCommand(anyString(), any())).thenReturn("{\"success\":true}");
        DocumentEditTools tools = toolsWithBridge(bridge);

        String out = tools.doc_get_paragraph(0);

        assertFalse(out.startsWith("Error"), "0 基的第一段不能被当成缺参，实际是：" + out);
        verify(bridge).executeEditorCommand(Mockito.eq("get_paragraph"), any());
    }
}
