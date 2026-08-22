package com.checkba.service.evidence;

import com.checkba.service.ai.EditorBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 「按引文在报告里建一条底稿链接」的唯一实现：查引文 → 选中 → 打书签（名 = linkKey）→ 套内部超链接
 * → 取章节上下文 → 落库。
 *
 * <p>抽出来之前这段只长在 {@code DocumentEditTools.doc_link_evidence} 里，插件想建链只能自己
 * 重走一遍 worker 原语，两份实现迟早漂移（书签名规则、超链接 scheme、章节路径口径都是契约）。
 * 现在 AI 工具与插件宿主都从这里走。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EvidenceAnchorService {

    /** 内部链接跳板地址：与前端 buildFileLinkUrl 同形，改这里等于改契约（有测试钉住）。 */
    public static final String INTERNAL_LINK_BASE = "https://checkba-internal.local/open";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EditorBridgeService editorBridgeService;
    private final EvidenceLinkService evidenceLinkService;

    /**
     * @param anchorQuote 报告原文片段，必须在文档中恰好出现一次；与 anchorId 二选一
     * @param anchorId    doc_find_text 拿到的锚点 id；给了就不再查找
     * @throws IllegalArgumentException 引文命中 0 处或多处（消息可直接回给模型/插件）
     */
    public EvidenceLinkViews.LinkView linkAtQuote(Long userId, Long projectId, Long docFileId,
                                                  String anchorQuote, String anchorId,
                                                  List<EvidenceLinkViews.TargetInput> targets,
                                                  String createdByKind) {
        boolean hasAnchor = anchorId != null && !anchorId.isBlank();
        String anchor = anchorId;
        String matchedText = null;
        if (!hasAnchor) {
            JsonNode found = workerJson(editorBridgeService.executeEditorCommand(
                    "find_text_locations", Map.of("keyword", anchorQuote.trim())));
            JsonNode matches = found.path("matches");
            int n = matches.isArray() ? matches.size() : 0;
            if (n == 0) {
                clearAnchorsQuietly();
                throw new IllegalArgumentException("anchorQuote 在文档中命中 0 处，未建链。请核对原文（标点、空格要一致），"
                        + "或先用 doc_find_text 定位后传 anchorId");
            }
            if (n > 1) {
                clearAnchorsQuietly();
                throw new IllegalArgumentException("anchorQuote 在文档中命中 " + n + " 处，无法唯一定位，未建链。"
                        + "请给更长、更独特的片段，或用 doc_find_text 挑出那一处后传 anchorId");
            }
            anchor = matches.get(0).path("anchorId").asText(null);
            matchedText = matches.get(0).path("text").asText(null);
            if (anchor == null || anchor.isBlank()) {
                throw new IllegalArgumentException("查找结果缺少 anchorId，未建链");
            }
        }

        workerJson(editorBridgeService.executeEditorCommand("set_selection", Map.of("anchor", anchor)));
        // 选区已经落定，查找留下的锚点标记可以清掉（顺序不能反：set_selection 靠 anchorId 定位）
        if (!hasAnchor) clearAnchorsQuietly();

        String linkKey = "EVID_" + Ulid.next();
        JsonNode bm = workerJson(editorBridgeService.executeEditorCommand("bookmark_selection", Map.of("name", linkKey)));
        String bookmarkText = bm.path("text").asText(null);

        String inner = "checkba://filelink?k=" + linkKey + "&projectId=" + projectId;
        String url = INTERNAL_LINK_BASE + "?u=" + URLEncoder.encode(inner, StandardCharsets.UTF_8);
        workerJson(editorBridgeService.executeEditorCommand("set_selection_hyperlink", Map.of("url", url)));

        String sectionPath = "";
        String sectionTitle = "";
        String contextText = null;
        try {
            JsonNode ctx = workerJson(editorBridgeService.executeEditorCommand("get_bookmark_context", Map.of("name", linkKey)));
            sectionPath = ctx.path("sectionPath").asText("");
            sectionTitle = ctx.path("sectionTitle").asText("");
            contextText = ctx.path("text").asText(null);
        } catch (IllegalStateException e) {
            // 书签与超链接已写入，章节信息拿不到不致命：sectionPath 留空，链照样落库
            log.warn("linkAtQuote: get_bookmark_context failed for {}: {}", linkKey, e.getMessage());
        }
        String anchorText = firstNonBlank(contextText, bookmarkText, matchedText, anchorQuote);

        return evidenceLinkService.create(userId, projectId, docFileId, linkKey, anchorText,
                sectionPath, sectionTitle, createdByKind, targets);
    }

    private void clearAnchorsQuietly() {
        try {
            editorBridgeService.executeEditorCommand("clear_anchors", Map.of());
        } catch (RuntimeException ignore) {
            // 清锚点失败不影响主流程
        }
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (x != null && !x.isBlank()) return x;
        return null;
    }

    private JsonNode workerJson(String raw) {
        try {
            JsonNode n = JSON.readTree(raw == null ? "{}" : raw);
            JsonNode err = n.get("error");
            if (err != null && !err.isNull()) throw new IllegalStateException(err.asText());
            return n;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("编辑器返回无法解析: " + e.getMessage());
        }
    }
}
