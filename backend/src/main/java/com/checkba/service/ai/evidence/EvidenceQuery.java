package com.checkba.service.ai.evidence;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * evidence.retrieve.v1 检索请求（RFC #14 讨论产出的稳定契约，勿随意改字段）。
 *
 * 契约与传输解耦：本地实现、远程服务、MCP 插件都消费同一份请求结构，
 * MCP 只是传输适配层之一，不是契约本身。
 *
 * @param workspaceId   工作区标识。本地实现解释为项目 ID（纯数字或 "project:<id>"）
 * @param query         检索文本
 * @param asOf          时点语义：只返回该日期（含）之前生效的证据；null = 现在
 * @param sourceFilters 来源过滤（如记忆作用域 project/file/global），空列表 = 不过滤
 * @param accessContext 访问上下文（userId/conversationId 等），实现据此做权限裁剪
 * @param limit         结果上限，非正数按实现默认值处理
 */
public record EvidenceQuery(
        String workspaceId,
        String query,
        LocalDate asOf,
        List<String> sourceFilters,
        Map<String, String> accessContext,
        int limit) {

    public EvidenceQuery {
        sourceFilters = sourceFilters == null ? List.of() : List.copyOf(sourceFilters);
        accessContext = accessContext == null ? Map.of() : Map.copyOf(accessContext);
    }
}
