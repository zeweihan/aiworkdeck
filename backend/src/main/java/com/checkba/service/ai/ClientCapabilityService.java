// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级客户端能力登记（Phase C：office_* 工具桥）。
 *
 * 背景：同一个后端同时服务两类文档编辑客户端——
 * 1. 主前端（嵌入式 LibreOffice 编辑器，LOWA）：执行 doc_* / sheet_* 远端指令；
 * 2. Office 任务窗格插件（Word）：执行 office_* 远端指令。
 * 远端执行类工具在客户端没有对应实现时就是死路径——模型调用后阻塞 30 秒
 * 超时空转（PptxEditTools 教训）。因此按会话记录客户端能力，
 * ToolRegistry 的三个消费点（getAllSpecifications / execute / resolve）据此过滤。
 *
 * 能力由 chat 请求的可选字段 clientCapability 声明（lowa / office / none），
 * 缺省 lowa 保持现状兼容（存量主前端不发送该字段）。
 *
 * 生命周期与 EditorBridgeService 的 conversationId 语义一致：按会话记录、
 * 进程内存态、不落库；条目极小（枚举值），不做主动清理。
 */
@Service
@Slf4j
public class ClientCapabilityService {

    /** 客户端能力档位 */
    public enum Capability {
        /** 主前端：嵌入式 LibreOffice 编辑器（默认，兼容存量客户端） */
        LOWA,
        /** Office 任务窗格插件（Word/Excel/PowerPoint，office_* 执行器） */
        OFFICE,
        /** 无文档编辑执行器（纯对话客户端） */
        NONE
    }

    /**
     * Office 插件会话的宿主细分（仅 Capability.OFFICE 有意义）。
     * 三类宿主的执行器命令集互不相通——Excel 会话里下发 Word 面的 office_replace_text
     * 与下发没有执行器的工具一样是 30 秒超时死路径，所以宿主也要参与工具可见性过滤。
     */
    public enum OfficeHost {
        /** Word 任务窗格（默认，兼容不上送 officeHost 的存量插件） */
        WORD,
        /** Excel 任务窗格（office_excel_* 执行器） */
        EXCEL,
        /** PowerPoint 任务窗格（office_ppt_* 执行器） */
        POWERPOINT
    }

    /**
     * Office 会话的宿主家族（仅 Capability.OFFICE 有意义，dev-board#298）。
     * 工具可见性不区分家族（office_command 契约两家族同构），只用于对话镜像的来源标注
     * （桌面端要能区分「Word 插件」与「WPS 文字插件」）。
     */
    public enum OfficeFamily {
        /** Microsoft Office 任务窗格（默认，兼容不上送 officeFamily 的存量插件）。 */
        OFFICE,
        /** WPS 加载项任务窗格。 */
        WPS
    }

    private final ConcurrentHashMap<String, Capability> byConversation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OfficeHost> hostByConversation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OfficeFamily> familyByConversation = new ConcurrentHashMap<>();

    /**
     * 记录一次 chat 请求声明的客户端能力（不带宿主细分，宿主按 WORD 兜底）。
     * raw 为空或无法识别时按 LOWA 处理（现状兼容）。
     */
    public void record(String conversationId, String raw) {
        record(conversationId, raw, null);
    }

    /**
     * 记录一次 chat 请求声明的客户端能力与 Office 宿主。
     * officeHostRaw 为空或无法识别时按 WORD 处理（存量 Word 插件不上送该字段）。
     */
    public void record(String conversationId, String raw, String officeHostRaw) {
        record(conversationId, raw, officeHostRaw, null);
    }

    /**
     * 全参重载：再带宿主家族（office / wps）。familyRaw 为空或无法识别时按 OFFICE 处理。
     */
    public void record(String conversationId, String raw, String officeHostRaw, String familyRaw) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        Capability parsed = parse(raw);
        Capability previous = byConversation.put(conversationId, parsed);
        if (previous != null && previous != parsed) {
            log.info("Client capability changed for conversation {}: {} -> {}", conversationId, previous, parsed);
        }
        OfficeHost parsedHost = parseHost(officeHostRaw);
        OfficeHost previousHost = hostByConversation.put(conversationId, parsedHost);
        if (previousHost != null && previousHost != parsedHost) {
            log.info("Office host changed for conversation {}: {} -> {}", conversationId, previousHost, parsedHost);
        }
        familyByConversation.put(conversationId, parseFamily(familyRaw));
    }

    /** Office 会话的宿主家族；未登记（含 conversationId 为 null）时默认 OFFICE。 */
    public OfficeFamily officeFamilyOf(String conversationId) {
        if (conversationId == null) {
            return OfficeFamily.OFFICE;
        }
        return familyByConversation.getOrDefault(conversationId, OfficeFamily.OFFICE);
    }

    /** 会话能力；未登记（含 conversationId 为 null）时默认 LOWA。 */
    public Capability capabilityOf(String conversationId) {
        if (conversationId == null) {
            return Capability.LOWA;
        }
        return byConversation.getOrDefault(conversationId, Capability.LOWA);
    }

    /** Office 会话的宿主；未登记（含 conversationId 为 null）时默认 WORD。 */
    public OfficeHost officeHostOf(String conversationId) {
        if (conversationId == null) {
            return OfficeHost.WORD;
        }
        return hostByConversation.getOrDefault(conversationId, OfficeHost.WORD);
    }

    /** LOWA 活跃文档类型三分值，与 {@link ContextAssemblerService#lowaDocKind} 的返回值同源。 */
    public static final String DOC_KIND_WRITER = "doc";
    public static final String DOC_KIND_SHEET = "sheet";
    public static final String DOC_KIND_SLIDE = "slide";

    /**
     * 与「当前打开的是哪一类文档」无关、任何 LOWA 会话都要保留的 doc_* / sheet_* 工具
     *（dev-board#729）。判据是<b>这个工具的效果不依赖活跃文档的类型</b>，分两类：
     *
     * <p><b>纯后端（不经 {@code EditorBridgeService.executeEditorCommand}）</b>——逐个核对过
     * {@code DocumentEditTools} / {@code CheckpointTools} 的 85 个 @Tool，真正纯后端的只有这几个：
     * <ul>
     *   <li>{@code doc_list_project_files} / {@code doc_open_file} / {@code doc_search_related_docs}
     *       —— 读 project_file 表 + 经 SSE 下发打开指令，是「换一份目标文档」的唯一入口，
     *       裁掉它 xlsx 会话里的模型就再也打不开任何 Word 文档了；</li>
     *   <li>{@code doc_restore_checkpoint} —— 按 fileId 还原本轮快照，与文档类型无关。
     *       Calc/Impress 没有修订痕迹、写入即生效，它就是那两类文档唯一的后悔药。</li>
     * </ul>
     *
     * <p><b>「新建并打开一份新文档」</b>——它们作用在新建出来的那份文件上，跟此刻开着什么无关：
     * <ul>
     *   <li>{@code sheet_create_file}（POI 建空白 xlsx + 注册 + 下发打开）——
     *       「把合同里的付款条款整理成一张表」在 Word 会话里是常见任务；</li>
     *   <li>{@code doc_start_stream}（建空白 docx + 打开 + 进流式写入模式）——
     *       「看着这份台账起草一份说明」在 Excel 会话里同样常见。它确实走桥，
     *       但走的是新建出来的那份 docx，不是活跃文档。</li>
     * </ul>
     * 少了这两个，跨类型的新建流程在第一步就被堵死；而它们一旦执行成功，
     * 活跃文档就换了类型，编排器会把工具集放回全集（见 {@code AgentOrchestrator} 的
     * {@code widenDocKindAfterDocumentSwitch}）。
     *
     * <p>其余 doc_* / sheet_* / slide_* 全部作用在活跃文档上，类型不匹配时一律是
     * 「worker 报错」或更糟的静默错改，模型看得见就会去试。
     */
    private static final java.util.Set<String> KIND_AGNOSTIC_LOWA_TOOLS = java.util.Set.of(
            "doc_list_project_files",
            "doc_open_file",
            "doc_search_related_docs",
            "doc_restore_checkpoint",
            "sheet_create_file",
            "doc_start_stream");

    /**
     * 工具对该会话是否可见。
     * doc_* / sheet_* / slide_* 是 LOWA 专属远端执行工具（经 EditorBridgeService 等前端回执）；
     * office_* 是 Office 插件专属（经 OfficeBridgeService 等插件回执），且按宿主再细分——
     * office_excel_* 只对 Excel 会话可见、office_ppt_* 只对 PowerPoint 会话可见、
     * 其余 office_*（Word 面）只对 Word 会话可见；
     * 其余工具（纯后端执行）对所有能力档位可见——包括 text_*（纯文本直读直写，
     * 后端 StorageService 落盘、无客户端执行器依赖，dev-board#37），刻意不过滤。
     */
    public boolean isToolVisible(String toolName, String conversationId) {
        return isToolVisible(toolName, conversationId, null);
    }

    /**
     * 再按 LOWA 活跃文档类型收窄（dev-board#729 ①）。
     *
     * <p>doc_* / sheet_* / slide_* 是**三套互不相通**的原语：Writer 文档上调 sheet_* 必然报
     * 「当前打开的不是电子表格」，Calc 上调 doc_* 连 {@code xModel.getText()} 都过不去。
     * 三套一起下发的代价是实打实的钱和时间——202 个工具的 schema 约占 prompt 的 2/3
     * （实测 59045 → 18854 token、首轮 26.4s → 6.1s），而其中三分之二在本轮一个字都用不上。
     *
     * @param activeDocKind {@link #DOC_KIND_WRITER} / {@link #DOC_KIND_SHEET} /
     *                      {@link #DOC_KIND_SLIDE}；null 或其它值（没有活跃文档、纯文本、
     *                      类型判不出来）一律**不裁剪**——少给工具会让模型直接做不成事，
     *                      判不准时必须倒向全集。
     */
    public boolean isToolVisible(String toolName, String conversationId, String activeDocKind) {
        if (toolName == null) {
            return false;
        }
        boolean lowaOnly = toolName.startsWith("doc_") || toolName.startsWith("sheet_") || toolName.startsWith("slide_");
        boolean officeOnly = toolName.startsWith("office_");
        if (!lowaOnly && !officeOnly) {
            return true;
        }
        return switch (capabilityOf(conversationId)) {
            case LOWA -> lowaOnly && visibleForDocKind(toolName, activeDocKind);
            case OFFICE -> officeOnly && hostOfTool(toolName) == officeHostOf(conversationId);
            case NONE -> false;
        };
    }

    /** 活跃文档类型闸：只收窄、绝不放宽（调用方已确认是 LOWA 会话的 lowaOnly 工具）。 */
    static boolean visibleForDocKind(String toolName, String activeDocKind) {
        if (activeDocKind == null || activeDocKind.isBlank()) {
            return true;
        }
        if (KIND_AGNOSTIC_LOWA_TOOLS.contains(toolName)) {
            return true;
        }
        return switch (activeDocKind) {
            case DOC_KIND_WRITER -> toolName.startsWith("doc_");
            case DOC_KIND_SHEET -> toolName.startsWith("sheet_");
            case DOC_KIND_SLIDE -> toolName.startsWith("slide_");
            // "text"（纯文本走 text_*，不进 LOWA）与任何未知值：判不准就不裁
            default -> true;
        };
    }

    /** office_* 工具所属宿主：按前缀细分（最长前缀优先，office_excel_ 也以 office_ 开头）。 */
    static OfficeHost hostOfTool(String toolName) {
        if (toolName.startsWith("office_excel_")) {
            return OfficeHost.EXCEL;
        }
        if (toolName.startsWith("office_ppt_")) {
            return OfficeHost.POWERPOINT;
        }
        return OfficeHost.WORD;
    }

    private static Capability parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Capability.LOWA;
        }
        try {
            return Capability.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown clientCapability '{}', falling back to LOWA", raw);
            return Capability.LOWA;
        }
    }

    private static OfficeFamily parseFamily(String raw) {
        if (raw == null || raw.isBlank()) {
            return OfficeFamily.OFFICE;
        }
        try {
            return OfficeFamily.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown officeFamily '{}', falling back to OFFICE", raw);
            return OfficeFamily.OFFICE;
        }
    }

    private static OfficeHost parseHost(String raw) {
        if (raw == null || raw.isBlank()) {
            return OfficeHost.WORD;
        }
        try {
            return OfficeHost.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown officeHost '{}', falling back to WORD", raw);
            return OfficeHost.WORD;
        }
    }
}
