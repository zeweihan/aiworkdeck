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

    private final ConcurrentHashMap<String, Capability> byConversation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OfficeHost> hostByConversation = new ConcurrentHashMap<>();

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
        boolean lowaOnly = toolName.startsWith("doc_") || toolName.startsWith("sheet_") || toolName.startsWith("slide_");
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
