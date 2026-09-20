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
        if (toolName == null) {
            return false;
        }
        boolean lowaOnly = isLowaTool(toolName);
        boolean officeOnly = toolName.startsWith("office_");
        if (!lowaOnly && !officeOnly) {
            return true;
        }
        return switch (capabilityOf(conversationId)) {
            case LOWA -> lowaOnly;
            case OFFICE -> officeOnly && hostOfTool(toolName) == officeHostOf(conversationId);
            case NONE -> false;
        };
    }

    /**
     * 是否是 LOWA 专属的远端执行工具（doc_* / sheet_* / slide_*）。
     *
     * <p>只回答「这个工具需不需要 LOWA 编辑器」，读写一视同仁——{@link #isToolVisible}
     * 要的就是这个：Office 会话里连 {@code doc_get_document_text} 都执行不了。
     *
     * <p><b>不要把它和 {@link #isDocumentWritingTool} 合成一个函数。</b>
     * 两个消费者问的是不同的问题，合并过一次就出过事：按这个宽判据算
     * {@code bubble_end.documentEdited}，「先读文档再起草条款」那一轮会因为
     * 调过 doc_get_document_text 被判成「改过文档」，回复下方的「用到文档」被误藏——
     * 而那恰恰是最该出按钮的场景（dev-board#728）。
     */
    public static boolean isLowaTool(String toolName) {
        return toolName != null
                && (toolName.startsWith("doc_")
                    || toolName.startsWith("sheet_")
                    || toolName.startsWith("slide_"));
    }

    /**
     * 读取 / 定位 / 打开类工具的名字模式（去掉 doc_ / sheet_ / slide_ 前缀之后的部分）。
     *
     * <p>按 DocumentEditTools、DocumentAuditTools、CheckpointTools、SlideEditTools 里
     * 全部 113 个 {@code doc_/sheet_/slide_} 工具名逐个核对得出，31 个只读、82 个写入；
     * {@code DocumentWritingToolClassificationTest} 扫源码逐名钉住，新增工具没归类就会红。
     *
     * <p><b>为什么不能只按「读起来像读」的词头一刀切</b>——两个真实的坑：
     * <ul>
     *   <li>{@code doc_find_replace} 以 {@code find_} 开头，却是全仓最常用的写入原语。
     *       所以 {@code find} 不是词头模式，只有 {@code find_text} 进精确名单；</li>
     *   <li>{@code doc_set_selection} 只挪选区（只读），而 {@code doc_replace_selection} /
     *       {@code doc_delete_selection} / {@code doc_format_selection} 都是写入。
     *       所以 {@code selection} 不能做子串匹配，只有 {@code set_selection} 进精确名单。</li>
     * </ul>
     *
     * <p>词头一律用 {@code (?:_|$)} 收尾，不做前缀模糊匹配：{@code inspect} 若松绑就会
     * 咬到 {@code insert_*}。{@code summar} 是唯一的例外（要同时覆盖 summary / summarize），
     * 目前没有工具命中，是给后来者留的。
     *
     * <p>拿不准的一律算写入（{@code doc_collapse_cursor} / {@code doc_undo} /
     * {@code doc_redo} / {@code doc_restore_checkpoint} 都在写入侧）：多算只是少出一个按钮，
     * 漏算会让用户在 AI 已经写进文档之后又被请去手动插一遍。
     */
    private static final java.util.regex.Pattern READ_ONLY_STEM = java.util.regex.Pattern.compile(
            "^(?:audit|check|count|debug|get|goto|inspect|list|locate|read|search|select)(?:_|$)|^summar");

    /** 以 read 结尾的读取工具（doc_table_read / slide_table_read）。 */
    private static final java.util.regex.Pattern READ_ONLY_SUFFIX =
            java.util.regex.Pattern.compile("(?:^|_)read$");

    /** 词头模式覆盖不到、必须逐个点名的只读工具（见上面两个坑）。 */
    private static final java.util.Set<String> READ_ONLY_EXACT =
            java.util.Set.of("open_file", "find_text", "set_selection");

    /**
     * 这个工具会不会真的改动文档内容。{@code bubble_end.documentEdited} 的唯一判据
     * （dev-board#728）：本轮调过它并成功返回，就说明 AI 已经把内容写进文档了，
     * 前端不必再请用户手动插一遍。
     *
     * <p>判据是<b>工具名</b>而不是 {@code @ToolMeta.fileEffect}：最常用的几个写入原语
     * （doc_insert_at_cursor / doc_replace_selection / doc_start_stream / doc_delete_text …）
     * 压根没声明 fileEffect（113 个里 45 个没有），按它判会把真正的编辑漏成「没动过」。
     */
    public static boolean isDocumentWritingTool(String toolName) {
        if (!isLowaTool(toolName)) {
            return false;
        }
        String action = toolName.substring(toolName.indexOf('_') + 1);
        return !(READ_ONLY_STEM.matcher(action).find()
                || READ_ONLY_SUFFIX.matcher(action).find()
                || READ_ONLY_EXACT.contains(action));
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
