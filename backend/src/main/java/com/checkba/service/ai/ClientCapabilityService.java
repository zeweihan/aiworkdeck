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
        /** Office 任务窗格插件（Word，office_* 执行器） */
        OFFICE,
        /** 无文档编辑执行器（纯对话客户端） */
        NONE
    }

    private final ConcurrentHashMap<String, Capability> byConversation = new ConcurrentHashMap<>();

    /**
     * 记录一次 chat 请求声明的客户端能力。
     * raw 为空或无法识别时按 LOWA 处理（现状兼容）。
     */
    public void record(String conversationId, String raw) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        Capability parsed = parse(raw);
        Capability previous = byConversation.put(conversationId, parsed);
        if (previous != null && previous != parsed) {
            log.info("Client capability changed for conversation {}: {} -> {}", conversationId, previous, parsed);
        }
    }

    /** 会话能力；未登记（含 conversationId 为 null）时默认 LOWA。 */
    public Capability capabilityOf(String conversationId) {
        if (conversationId == null) {
            return Capability.LOWA;
        }
        return byConversation.getOrDefault(conversationId, Capability.LOWA);
    }

    /**
     * 工具对该会话是否可见。
     * doc_* / sheet_* 是 LOWA 专属远端执行工具（经 EditorBridgeService 等前端回执）；
     * office_* 是 Office 插件专属（经 OfficeBridgeService 等插件回执）；
     * 其余工具（纯后端执行）对所有能力档位可见。
     */
    public boolean isToolVisible(String toolName, String conversationId) {
        if (toolName == null) {
            return false;
        }
        boolean lowaOnly = toolName.startsWith("doc_") || toolName.startsWith("sheet_");
        boolean officeOnly = toolName.startsWith("office_");
        if (!lowaOnly && !officeOnly) {
            return true;
        }
        return switch (capabilityOf(conversationId)) {
            case LOWA -> lowaOnly;
            case OFFICE -> officeOnly;
            case NONE -> false;
        };
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
}
