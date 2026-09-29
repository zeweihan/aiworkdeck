// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ProjectFileService;
import com.checkba.model.entity.ProjectFile;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Legal Tools Set for the Agent.
 * Includes:
 * 1. File Operations (Read Document) - using Tika or simple read
 * 2. PKULaw MCP Integration - 只做法律语义封装，协议细节在 service/ai/mcp 包
 *    （服务器列表见配置 mcp.servers，传输实现见 McpProvider）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegalTools implements AgentToolComponent {

    private final ProjectFileService projectFileService;
    private final com.checkba.service.legal.PkulawChannel pkulawChannel;
    /**
     * 抽取路由（PDF 文字层优先、扫描件才 OCR）与抽取结果缓存，与 {@code extract_file_text}
     * 是同一个 bean（dev-board#800）——{@code read_document} 不再自建第二条分支。
     */
    private final com.checkba.service.file.ProjectFileTextExtractor textExtractor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.service.platform.ExternalServiceAvailability externalServiceAvailability;

    /** 平台档下法宝检索必须经网关，没连账户时四个 law_* 工具每次都只会回一句「不可用」。 */
    private static final java.util.Set<String> PKULAW_TOOLS =
            java.util.Set.of("law_search", "law_search_keyword", "law_recognition", "get_law_article");

    /**
     * 没连账户时不把法宝检索下发给模型（dev-board#750）。{@code read_document} 读的是项目里
     * 已有的文件、全程不出网，绝不能跟着一起藏——那会让模型以为它连文件都读不了。
     */
    @Override
    public java.util.Set<String> currentlyUnusableTools() {
        if (externalServiceAvailability != null
                && !externalServiceAvailability.usable(
                        com.checkba.service.platform.ExternalServiceProvider.PKULAW)) {
            return PKULAW_TOOLS;
        }
        return java.util.Set.of();
    }

    // --- File Operations ---

    /**
     * 读取项目文件正文。
     *
     * <p><b>只登记不下发</b>（dev-board#1065，审计 T-05）：它与 {@code extract_file_text} 是同一个抽取器的
     * 两个入口，两份几乎同义的描述摆在模型面前，选哪个近乎随机。唯一的实质差异——纯文本的
     * 「UTF-8 严格解码、失败回退 GBK」——已并进 {@link com.checkba.service.file.ProjectFileTextExtractor}，
     * 两个入口逐字同一份正文（{@code GbkPlainTextParityTest} 钉住）。方法本身保留：上下文组装
     *（附件与活跃文档注入）、run_python 的 default_api 与老会话的 XML 兜底都还在调它。
     *
     * <p>全部格式走 {@link com.checkba.service.file.ProjectFileTextExtractor}：纯文本按字节解码，
     * 图片直接云端 OCR，<b>PDF 先抽文字层、抽不出（扫描件）才 OCR</b>，其余（docx/xlsx/pptx/doc 等
     * Office 格式）Tika。
     *
     * <p>Office 那条曾经不存在：docx 两个白名单都不在，恒定落进
     * {@code FileContentExtractorService.extractText} 的 else 分支返回空串——
     * 而空串会被 {@code ToolExecutionResultMessage.from} 的 ensureNotBlank 抛出来掀翻整轮
     * （用户看到「Callback Error: text cannot be null or blank」），
     * 同时 Active Document 注入的正文也恒为空，模型转头自己再调一次本工具。
     *
     * <p>PDF 那条在 dev-board#800 之前是<b>无条件逐页 150DPI 渲染 + 云端 OCR、只看前 20 页</b>：
     * 带文字层的合同与裁判文书本来毫秒级就能读，却每轮都要等完整 OCR、每轮按页扣 Credits，
     * 而且同一份 PDF 走 {@code extract_file_text} 得到的正文与账单完全不同。现在三条入口同一口径。
     *
     * <p>音频（mp3/m4a/wav…）在 dev-board#814 之前落进 Tika 抽出空串，于是回的是下面那句
     * 指向 OCR 与 extract_file_text 的 Warning——对音频三条建议没有一条成立。现在走抽取器的
     * 音频分支：已转写的直接返回转写稿，没转写的回一句「先转写、右键『转写音频』」。
     *
     * <p>抽不出正文时<b>绝不返回空白</b>，而是给一句可行动的说明（口径抄 extract_file_text）；
     * OCR 失败一律 {@code Error:} 开头并带上底层原因——此前它以「[System: OCR 识别失败…]」形态
     * 返回，非空且无 Error 前缀，会被当成正文原样注进上下文。
     */
    @ToolMeta(displayName = "读取文档", category = "file", offerToModel = false)
    @Tool("Read a project file's full plain text by its database fileId (from doc_list_project_files). "
            + "Handles Word/Excel/PowerPoint, PDF and plain text; images and scanned PDFs are OCR'd "
            + "automatically in the cloud (no local setup, no Docker, no script). "
            + "Very long documents are truncated (the reply says so; ask for a specific section instead of "
            + "re-reading the whole file). "
            + "**If the id might be a FOLDER, call extract_file_text instead** — it is the same extractor but "
            + "answers a folder id with a listing of its children (id + name + type) rather than an error.")
    public String read_document(String fileId) {
        log.info("Tool: read_document called for fileId={}", fileId);
        try {
            Long fId = Long.parseLong(fileId);
            ProjectFile file = projectFileService.getFile(fId);
            if (file == null) return "Error: File not found.";
            String denied = ToolFileGuard.rejectIfOutsideProject(file);
            if (denied != null) return denied;

            String name = file.getName();
            String result;
            try {
                result = textExtractor.extractText(file);
            } catch (com.checkba.service.file.ProjectFileTextExtractor
                    .AudioNotTranscribedException e) {
                // 音频没有转写稿不是错误，文件本身好好的（dev-board#814）。用 Warning:
                // 而不是 Error:，两者都会被 ContextAssembler 的失败回执守卫认出来、
                // 不进 <file> 的 CDATA，但对模型（以及过程卡里的用户）语气不同。
                return "Warning: " + e.getMessage();
            } catch (com.checkba.service.file.ProjectFileTextExtractor.OcrFailedException e) {
                return "Error: " + e.getMessage();
            }

            if (!StringUtils.hasText(result)) {
                return "Warning: no text extracted from '" + name + "' — the file may be empty, or an image "
                        + "whose OCR recognised nothing (images and scanned PDFs are OCR'd automatically here); "
                        + "you can also try extract_file_text with its database file ID.";
            }
            // 上限与 extract_file_text 同源：不截断的话，一份几 MB 的合同会变成一条几十万
            // 字符的工具结果，下一轮必然上下文超限，且它落在 compactor 尾区剪不掉 = 整轮死
            return ToolFileGuard.capToolText(name, fId, result);

        } catch (Exception e) {
            log.error("Failed to read document {}", fileId, e);
            return "Error reading document: " + e.getMessage();
        }
    }

    // --- PKULaw MCP Integration（服务器名对应配置 mcp.servers[].name）---

    @ToolMeta(displayName = "语义搜索法规", category = "legal")
    @Tool("Search for laws and regulations using PKULaw MCP Semantic Search. Use this for general legal questions. Returns a list of relevant articles.")
    public String law_search(@P("要检索的法律问题或关键词，用自然语言描述即可") String query) {
        log.info("Tool: law_search (semantic) called for query='{}'", query);
        // 先校验再解引用：query 缺省时 Map.of("query", query) 会直接抛 NPE（getMessage()==null），
        // 被 ToolRegistry 的通用异常处理兜成一句不可行动的 "Error executing tool: null"——
        // 模型不知道到底缺了哪个参数。同款问题与修法见 get_law_article/law_recognition（审计条目）。
        if (!StringUtils.hasText(query)) {
            return "Error: query is required.";
        }
        return callPkulaw("pkulaw-semantic", "search_article", Map.of("query", query));
    }

    @ToolMeta(displayName = "关键词搜索法规", category = "legal")
    @Tool("Search for laws by keywords in title or fulltext. Use this when you need specific laws by name.")
    public String law_search_keyword(
            @P(value = "法规名称中的关键词（如「公司法」）；与 fulltext 至少给一个", required = false) String title,
            @P(value = "法规正文中的关键词；与 title 至少给一个", required = false) String fulltext) {
        log.info("Tool: law_search_keyword called for title='{}', fulltext='{}'", title, fulltext);
        Map<String, Object> args = new HashMap<>();
        if (StringUtils.hasText(title)) args.put("title", title);
        if (StringUtils.hasText(fulltext)) args.put("fulltext", fulltext);

        return callPkulaw("pkulaw-keyword", "get_law_list", args);
    }

    @ToolMeta(displayName = "法条识别与溯源", category = "legal")
    @Tool("Identify law names and articles from text and trace their source.")
    public String law_recognition(@P("要识别其中法规名称与条文号的原文") String text) {
        // text.length() 曾经排在校验之前：text 缺省时这里直接 NPE，比下面的 Map.of 更早触发。
        if (!StringUtils.hasText(text)) {
            return "Error: text is required.";
        }
        log.info("Tool: law_recognition called for text length={}", text.length());
        return callPkulaw("pkulaw-recognition", "law_recognition", Map.of("text", text));
    }

    @ToolMeta(displayName = "查询法条", category = "legal")
    @Tool("Get the full content of a specific law article by its title and article number. Use this when you have article info from law_search results.")
    public String get_law_article(
            @P("法规全称（如「中华人民共和国民法典」），可取自 law_search 的结果") String title,
            @P("条文号（如「第五百七十七条」或「577」）") String number) {
        log.info("Tool: get_law_article called for title='{}', number='{}'", title, number);
        // 模型常见的调用形状：只给 title 漏给 number（工具描述没标两者都必填）。ToolRegistry.bindArguments
        // 对缺省的非基本类型参数绑 null，Map.of("title", title, "number", null) 直接抛
        // NullPointerException（getMessage()==null），外层只会看到 "Error executing tool: null"——
        // 与 qichacha_query/tushare_query 同款先校验再用的口径对齐，缺哪个就点名哪个。
        if (!StringUtils.hasText(title)) {
            return "Error: title is required.";
        }
        if (!StringUtils.hasText(number)) {
            return "Error: number is required.";
        }
        return callPkulaw("pkulaw-semantic", "get_article", Map.of("title", title, "number", number));
    }

    /**
     * 法宝检索的双档分发，分发本身在
     * {@link com.checkba.service.legal.PkulawChannel}（依据窗格与本工具共用，dev-board#395）。
     *
     * <p>网关失败<b>不抛异常打断整轮对话</b>：这是给模型看的文本，
     * 说清楚发生了什么、下一步是什么，让它基于已有信息继续——
     * 与「未配置」那条既有分支同一口径（licensing-billing 地雷 27）。
     */
    private String callPkulaw(String server, String tool, Map<String, Object> args) {
        try {
            return pkulawChannel.callTool(server, tool, args);
        } catch (com.checkba.service.platform.GatewayException e) {
            log.warn("平台法规检索失败 kind={}: {}", e.getKind(), e.getMessage());
            // 刻意不带 Error / 错误 前缀（dev-board#1065 T-11 裁决）：这是「跳过法规检索、继续干活」的
            // 软失败，判成失败会让连续失败纠正回路催模型换思路，而正确的下一步正是照常把任务做完。
            // ToolFailureClassificationTest 的源码扫描把这一处列在白名单里，改文案时同步那里。
            return "法规检索本次不可用：" + e.getMessage() + e.userHint()
                    + " 本次已跳过法规检索，请基于已有信息继续完成任务。";
        }
    }
}
