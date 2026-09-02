package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.review.ContractStructureAudit;
import com.checkba.service.ai.review.ContractStructureAudit.Paragraph;
import com.checkba.service.ai.review.ContractStructureAudit.Revision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同审查的「机械核对」工具（dev-board#375）：把全文从编辑器分页拉完，交给
 * {@link ContractStructureAudit} 做确定性检查，报告回给模型。
 *
 * <p>为什么要有它：模型分页读正文（每页 15000 字符）时数编号、核算式、找悬空引用靠肉眼，
 * 弱模型必漏、强模型也要烧步数。Claude Code 审同一份合约时是临时写脚本做这些事的，
 * 工作台里模型没有脚本可写，就把脚本做成工具。
 *
 * <p>只读：不改文档、不建检查点。走 LOWA worker 的 get_document_text / list_revisions 两个既有
 * action，不新增 worker 契约。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentAuditTools implements AgentToolComponent {

    /** 分页读取每页段落数（worker 上限 500）与最多页数（500 × 60 = 3 万段，远超任何合同）。 */
    static final int PAGE_SIZE = 500;
    static final int MAX_PAGES = 60;
    static final int REVISION_LIMIT = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EditorBridgeService editorBridgeService;

    @ToolMeta(displayName = "结构审计", category = "document")
    @Tool("【看·审查合同必用】对当前打开的文档做一次机械核对并返回报告：全文字形（繁體/简体）与混入段落、"
            + "各套条款编号是否连续（第X条 / N. / N.M / (一) / (a) / (1)）、正文引用的「第X条」「附表X」是否都存在、"
            + "空白与待定处、金额/股数台账与同段「股数×每股价=总价」算术复核、多币种并存、前一轮修订按作者/类型汇总与大段删除。"
            + "只报事实不下法律结论；审查/审阅合同时在通读之后、动手修改之前调用一次，把报告里的每一项都核成结论或排除。"
            + "本工具自己会把全文分页读完，不需要先调 doc_get_document_text。")
    public String doc_audit_structure() {
        log.info("Tool: doc_audit_structure called");
        List<Paragraph> paragraphs = new ArrayList<>();
        int start = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new HashMap<>();
            params.put("startParagraph", start);
            params.put("maxParagraphs", PAGE_SIZE);
            String raw;
            try {
                raw = editorBridgeService.executeEditorCommand("get_document_text", params);
            } catch (Exception e) {
                log.error("doc_audit_structure: get_document_text failed", e);
                return "Error: " + e.getMessage();
            }
            JsonNode node = parse(raw);
            if (node == null) {
                return "Error: 编辑器返回了无法解析的内容：" + head(raw);
            }
            if (node.hasNonNull("error")) {
                return "Error: " + node.get("error").asText();
            }
            JsonNode arr = node.get("paragraphs");
            if (arr == null || !arr.isArray()) {
                return "Error: 编辑器没有返回段落列表（当前文档可能不是 Word 文档，或没有打开文档）";
            }
            int before = paragraphs.size();
            for (JsonNode p : arr) {
                paragraphs.add(new Paragraph(p.path("index").asInt(paragraphs.size()), p.path("text").asText("")));
            }
            if (!node.path("truncated").asBoolean(false) || paragraphs.size() == before) {
                break;
            }
            start = node.path("nextStartParagraph").asInt(paragraphs.size());
        }
        if (paragraphs.isEmpty()) {
            return "Error: 文档没有可读的段落";
        }

        List<Revision> revisions = new ArrayList<>();
        String revisionNote = null;
        try {
            String raw = editorBridgeService.executeEditorCommand("list_revisions", Map.of("limit", REVISION_LIMIT));
            JsonNode node = parse(raw);
            if (node == null || node.hasNonNull("error") || !node.path("revisions").isArray()) {
                revisionNote = "（修订清单读取失败，已跳过：" + head(raw) + "）";
            } else {
                for (JsonNode rv : node.get("revisions")) {
                    revisions.add(new Revision(rv.path("type").asText(""), rv.path("author").asText(""),
                            rv.path("text").asText(""), rv.path("paragraph").asText("")));
                }
            }
        } catch (Exception e) {
            log.warn("doc_audit_structure: list_revisions failed: {}", e.getMessage());
            revisionNote = "（修订清单读取失败，已跳过：" + e.getMessage() + "）";
        }

        return ContractStructureAudit.run(paragraphs, revisions, revisionNote).render();
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String head(String raw) {
        if (raw == null) return "null";
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "…";
    }
}
