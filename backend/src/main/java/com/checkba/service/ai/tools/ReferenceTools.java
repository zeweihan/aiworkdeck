// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ref.RefQuery;
import com.checkba.service.ai.ref.ReferenceSourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参考来源工具（dev-board#717-720）：Office/WPS 任务窗格会话读其他文件、改其他打开着的文档。
 *
 * <p>可见性：ref_* 只对 clientCapability=office 的会话可见（{@link com.checkba.service.ai.ClientCapabilityService}），
 * LOWA 会话已有项目文件工具，不引入。
 *
 * <p>userId / projectId / conversationId 由 ToolRegistry 从服务端上下文强制注入（SERVER_CONTEXT_PARAMS），
 * 模型传入的同名值一律被忽略——防止拿别人的会话或项目当参考来源。
 */
@Component
@RequiredArgsConstructor
public class ReferenceTools implements AgentToolComponent {

    private final ReferenceSourceService referenceSourceService;
    private final ObjectMapper objectMapper;

    @Tool("列出可作为参考材料的文件：其他打开着的 Office/WPS 文档、桌面端项目文件、云端项目文件、官方案件库、已关联的 git 仓库。"
            + "query 为文件名关键字，可空；source 可选 open/desk/cloud/case/git。返回每行一个 ref，读取或修改时原样使用 ref。")
    @ToolMeta(displayName = "查找参考文件", category = "reference")
    public String ref_list(
            @P(value = "文件名关键字，可空（不过滤）", required = false) String query,
            @P(value = "只查某一个来源：open / desk / cloud / case / git，可空（全部来源）", required = false) String source,
            Long userId,
            Long projectId,
            String conversationId
    ) {
        String keyword = query == null || query.isBlank() ? null : query.trim();
        return referenceSourceService.list(new RefQuery(userId, projectId, conversationId, keyword), source);
    }

    @Tool("读取参考文件的文字。ref 来自 ref_list。locator 可选：page:N（Word 页）、slide:N（演示稿页）、"
            + "sheet:名称 或 sheet:名称!A1:D20（表格）、heading:标题文字。按页定位只对打开着的文档有效。")
    @ToolMeta(displayName = "读取参考文件", category = "reference")
    public String ref_read(
            @P("ref_list 返回的 ref，原样复制") String ref,
            @P(value = "定位：page:N / slide:N / sheet:名称[!A1:D20] / heading:标题文字，可空（全文）", required = false) String locator,
            Long userId,
            Long projectId,
            String conversationId
    ) {
        return referenceSourceService.read(context(userId, projectId, conversationId), ref, blankToNull(locator));
    }

    @Tool("修改另一个打开着的文档（只允许 open: 开头的 ref）。command 与 args 使用该文档所在软件对应的 office 命令名与参数"
            + "（与 office_* 工具下发的命令相同，例如 replace_text、excel_set_values、ppt_replace_text），argsJson 为 JSON 对象。"
            + "修改会以修订/修订记录的形式显示在那个文档自己的窗格里。")
    @ToolMeta(displayName = "修改其他打开的文档", category = "reference")
    public String ref_edit(
            @P("ref_list 返回的 open: 开头的 ref，原样复制") String ref,
            @P("目标文档所在软件对应的 office 命令名，例如 replace_text / excel_set_values / ppt_replace_text") String command,
            @P(value = "命令参数，JSON 对象（与对应 office_* 工具的参数相同）", required = false) String argsJson,
            Long userId,
            Long projectId,
            String conversationId
    ) {
        if (command == null || command.isBlank()) {
            return "错误：command 不能为空，请填写目标文档所在软件对应的 office 命令名（例如 replace_text）。";
        }
        Map<String, Object> args = parseArgs(argsJson);
        if (args == null) {
            return "错误：args 必须是 JSON 对象";
        }
        return referenceSourceService.edit(context(userId, projectId, conversationId), ref, command.trim(), args);
    }

    @Tool("请桌面端用系统默认程序打开项目里的文件（只允许 ref_list 标了 openable 的 desk: ref），"
            + "用于用户要求修改一个尚未打开的文件时。只能打开文档类文件（Word/Excel/PPT/PDF/文本等），"
            + "桌面端会拒绝其余类型。打开后请用户在该文档里打开 AI WorkDeck 窗格。")
    @ToolMeta(displayName = "在桌面端打开文件", category = "reference")
    public String ref_open(
            @P("ref_list 返回的、标了 openable 的 desk: ref，原样复制") String ref,
            Long userId,
            Long projectId,
            String conversationId
    ) {
        return referenceSourceService.open(context(userId, projectId, conversationId), ref);
    }

    private static RefQuery context(Long userId, Long projectId, String conversationId) {
        return new RefQuery(userId, projectId, conversationId, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 空白视为无参数（{}）；非 JSON 或不是对象时返回 null。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            if (node == null || !node.isObject()) {
                return null;
            }
            return objectMapper.convertValue(node, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
