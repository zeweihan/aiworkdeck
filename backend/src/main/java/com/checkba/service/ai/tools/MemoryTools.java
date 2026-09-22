// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ConversationSummary;
import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.ProjectMemory;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.memory.AgenticRetriever;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.memory.ProjectMemoryExtractor;
import com.checkba.service.ai.memory.document.MemoryDocumentService;
import com.checkba.service.ai.memory.document.MemoryFileView;
import com.checkba.service.ai.memory.document.MemorySpaceView;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 增强版记忆工具
 * 提供结构化的长期记忆存储和检索能力
 * 
 * 工具列表：
 * 1. save_memory - 保存重要信息到项目记忆
 * 2. query_memory - 查询项目记忆（三档算法由 depth 参数选，见下）
 * 3. get_project_context - 获取项目核心信息
 * 4. update_project_info - 更新项目信息
 * 5. get_conversation_summary - 获取对话摘要
 * 6. search_knowledge_base / deep_search - query_memory 的旧入口，只登记不下发
 *
 * <p><b>「从记忆里找东西」只剩一个工具</b>（dev-board#807，审计 A13）。此前
 * query_memory / search_knowledge_base / deep_search 三个的入参几乎一样，
 * 三段描述里关于「什么时候用我」的话又只有「回顾之前的决策」「结合关键词和语义理解」
 * 「需要更全面的信息时」——模型没有可操作的判据，实测就是随机挑一个，召回质量因此
 * 不可预期；更糟的是它往往连着调两三个，把步数预算和 token 一起花掉，而 deep_search
 * 每次还要额外起一次辅助模型做查询扩展（真实的时延与费用）。
 *
 * <p>现在三档算法收敛成 {@code query_memory} 的一个 {@code depth} 参数：
 * quick（关键词，今天 query_memory 的行为）/ hybrid（RRF 融合，原 search_knowledge_base）/
 * deep（Agentic 多轮召回，原 deep_search）。「要不要多花一次 LLM」从一道选工具的谜题
 * 变成一个模型能明确判断的参数。两个旧名保留为 {@code offerToModel = false} 的兼容入口：
 * 规格不下发（模型看不见即不会去试），但老会话回放 / XML 兜底路径调到时照常执行，
 * 并在结果里附一句指路——好过一句 "Tool not found" 让模型以为这个能力整个不存在。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MemoryTools implements AgentToolComponent {

    private final MemoryManager memoryManager;
    private final ProjectMemoryExtractor projectMemoryExtractor;
    private final AgenticRetriever agenticRetriever;

    @Autowired(required = false)
    private MemoryDocumentService memoryDocumentService;

    /**
     * 两个兼容入口在结果末尾附的指路（审计 A13）。
     *
     * <p>放在<b>末尾</b>而不是开头：模型读到的最后一句最管用，而这句的作用是
     * 「这次的结果照收，下次别再找这个名字」。不写的话模型会继续在历史里照抄旧名字，
     * 而旧名字的规格已经不下发了——它只是在不停地撞一个自己看不见的门。
     */
    private static final String MERGED_INTO_QUERY_MEMORY =
            "\n(提示：本工具已并入 query_memory，下次请直接调 "
                    + "query_memory(query=..., depth=\"hybrid\"/\"deep\")。)";

    /**
     * 保存结构化记忆
     */
    @Tool("保存重要信息到记忆中。用于存储关键决策、结论、事实、法律引用、用户偏好等需要长期保留的信息。" +
          "通过 scope 参数指定记忆归属：用户个人习惯用 user，项目事实用 project（默认），通用法律知识用 global。")
    @ToolMeta(displayName = "保存记忆", category = "memory")
    public String save_memory(
            @P("记忆类型: decision(决策)/conclusion(结论)/fact(事实)/reference(法律引用)/preference(偏好)") String type,
            @P("记忆标题或关键词，用于后续检索") String key,
            @P("记忆内容，详细描述需要保存的信息") String value,
            @P("是否为法律关键信息需要特别保护（如法条引用、金额、日期等），受保护信息在压缩时不会丢失") boolean isProtected,
            @P("记忆作用域(可选，默认project): user(用户级，跨项目的个人偏好与习惯)/project(项目级)/conversation(仅本对话)/file(绑定文件)/global(通用领域知识)") String scope,
            @P("来源文件ID(可选)，scope=file 时必填，将记忆绑定到具体文件") Long sourceFileId
    ) {
        log.info("Tool: save_memory called type={}, key={}, protected={}, scope={}", type, key, isProtected, scope);

        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        String conversationId = ProjectContextHolder.getConversationId();
        Long userId = ProjectContextHolder.getUserId();

        // 归一化作用域，非法值回落到项目级
        String normalizedScope = (scope != null && MemoryEntry.MemoryScope.isValid(scope))
                ? scope.toLowerCase() : MemoryEntry.MemoryScope.PROJECT;

        // 用户级/通用知识不强依赖项目上下文，其余作用域必须有项目ID
        boolean projectFree = MemoryEntry.MemoryScope.USER.equals(normalizedScope)
                || MemoryEntry.MemoryScope.GLOBAL.equals(normalizedScope);
        if (projectId == null && !projectFree) {
            return "错误：无法获取当前项目ID，请确保在项目上下文中使用此工具。";
        }
        if (MemoryEntry.MemoryScope.USER.equals(normalizedScope) && userId == null) {
            return "错误：无法获取当前用户ID，无法保存用户级记忆。";
        }
        if (MemoryEntry.MemoryScope.FILE.equals(normalizedScope) && sourceFileId == null) {
            return "错误：文件级记忆(scope=file)必须提供 sourceFileId。";
        }

        // 验证类型
        if (!isValidMemoryType(type)) {
            return "错误：无效的记忆类型。请使用: decision, conclusion, fact, reference, preference";
        }

        try {
            MemoryEntry entry = MemoryEntry.builder()
                    .projectId(projectId)
                    .userId(userId)
                    .conversationId(conversationId)
                    .memoryType(type.toLowerCase())
                    .memoryKey(key)
                    .memoryValue(value)
                    .isProtected(isProtected)
                    .importanceScore(isProtected ? 1.0 : 0.7)
                    .scope(normalizedScope)
                    .sourceFileId(sourceFileId)
                    .build();

            memoryManager.saveMemory(entry);

            return String.format("✓ 记忆已保存\n- 类型: %s\n- 作用域: %s\n- 关键词: %s\n- 受保护: %s",
                    type, normalizedScope, key, isProtected ? "是" : "否");
        } catch (Exception e) {
            log.error("Failed to save memory: {}", e.getMessage(), e);
            return "保存记忆时出错: " + e.getMessage();
        }
    }

    /**
     * 获取用户画像（跨项目的用户级记忆）
     */
    @Tool("获取当前用户的画像信息：跨项目的用户偏好、行文习惯、常用表达等。在需要个性化输出（如按用户习惯起草文书）时使用。")
    @ToolMeta(displayName = "获取用户画像", category = "memory")
    public String get_user_profile() {
        log.info("Tool: get_user_profile called");

        Long userId = ProjectContextHolder.getUserId();
        if (userId == null) {
            return "错误：无法获取当前用户ID。";
        }

        try {
            StringBuilder sb = new StringBuilder("# 用户画像\n\n");
            boolean hasContent = false;

            // 1. UserMemory 结构化偏好
            Optional<com.checkba.model.entity.UserMemory> umOpt = memoryManager.getUserMemory(userId);
            if (umOpt.isPresent() && umOpt.get().getPreferences() != null && !umOpt.get().getPreferences().isEmpty()) {
                sb.append("## 偏好设置\n");
                umOpt.get().getPreferences().forEach((k, v) ->
                        sb.append("- ").append(k).append(": ").append(v).append("\n"));
                hasContent = true;
            }

            // 2. 用户级记忆条目
            List<MemoryEntry> userMemories = memoryManager.retrieveUserMemories(userId, 20);
            if (!userMemories.isEmpty()) {
                sb.append("\n## 用户级记忆（跨项目）\n");
                for (MemoryEntry mem : userMemories) {
                    sb.append("- [").append(mem.getMemoryType()).append("] ");
                    if (mem.getMemoryKey() != null) {
                        sb.append(mem.getMemoryKey()).append(": ");
                    }
                    sb.append(mem.getMemoryValue()).append("\n");
                }
                hasContent = true;
            }

            if (!hasContent) {
                return "当前用户暂无画像信息。可以使用 save_memory(scope=\"user\") 保存用户偏好与习惯。";
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to get user profile: {}", e.getMessage(), e);
            return "获取用户画像时出错: " + e.getMessage();
        }
    }

    /**
     * 查询项目记忆
     */
    @Tool("在本项目的记忆里找此前的决策、结论、事实与约定。**这是检索项目记忆的唯一工具**，"
            + "三档检索算法用 depth 选，不要再去找别的记忆检索工具：\n"
            + "- depth=quick（默认）：关键词精确召回。最快最省，什么都不花。"
            + "用户用的词很可能就是当初存下来的词时（人名、公司名、条款名、金额）选它。\n"
            + "- depth=hybrid：关键词 + 语义 RRF 融合。quick 没找到、或者用户是用自己的说法转述"
            + "（「当时怎么定的价」而记忆里写的是「对价」）时选它。仍然不花额外的模型调用。\n"
            + "- depth=deep：多轮召回，**会额外起一次辅助模型做查询扩展**（真实的时延与费用）。"
            + "只在 quick/hybrid 都没捞到、且这件事确实值得多花一次调用时选它；"
            + "不要一上来就用 deep，也不要三档挨个试一遍。\n"
            + "若要可靠地找回某次 save_memory 明确绑定过的 file/conversation 作用域记忆，"
            + "请传 scope（与 sourceFileId）——否则三档都只能靠相关性碰运气，可能命中不了。")
    public String query_memory(
            @P("查询关键词，用于搜索相关记忆") String query,
            @P("记忆类型过滤(可选): decision/conclusion/fact/reference/preference/all，默认为all") String type,
            @P("按作用域精确定位(可选): file(需配 sourceFileId)/conversation(取当前对话)，"
                    + "不传则和此前一样只按关键词在全项目范围检索") String scope,
            @P("来源文件ID(可选)，scope=file 时必填") Long sourceFileId,
            @P("检索档位(可选): quick(默认,关键词)/hybrid(关键词+语义融合)/deep(多轮召回，额外一次模型调用)") String depth,
            @P("返回条数(可选,1-20)；不传按档位取默认值") Integer limit
    ) {
        log.info("Tool: query_memory called query='{}', type='{}', scope='{}', depth='{}'", query, type, scope, depth);
        return retrieveWithDepth(query, type, scope, sourceFileId, depth, limit);
    }

    /** 三档检索的唯一实现。depth 认不出来一律回落 quick——检索档位填错绝不该让整次调用失败。 */
    private String retrieveWithDepth(String query, String type, String scope, Long sourceFileId,
                                     String depth, Integer limit) {
        Long projectId = ProjectContextHolder.getProjectIdAsLong();

        if (projectId == null) {
            return "错误：无法获取当前项目ID。";
        }

        String mode = depth == null ? "quick" : depth.trim().toLowerCase();
        try {
            if ("hybrid".equals(mode)) {
                return formatFlatList("混合检索结果 (RRF 融合，",
                        memoryManager.hybridSearch(projectId, query, boundedLimit(limit, 5)),
                        projectId, scope, sourceFileId, 200, false);
            }
            if ("deep".equals(mode)) {
                return formatFlatList("深度检索结果 (Agentic 多轮召回，",
                        agenticRetriever.agenticRetrieve(projectId, query, boundedLimit(limit, 10)),
                        projectId, scope, sourceFileId, 300, true);
            }
            String memoryType = "all".equalsIgnoreCase(type) || type == null ? null : type.toLowerCase();
            List<MemoryEntry> memories = memoryManager.retrieveMemories(
                    projectId, query, memoryType, boundedLimit(limit, 10));
            memories = withScopedMemories(memories, projectId, scope, sourceFileId);

            if (memories.isEmpty()) {
                return "未找到相关记忆。可以先用 depth=hybrid 再试一次；"
                        + "仍然没有就用 save_memory 把这次的结论存下来。";
            }

            StringBuilder sb = new StringBuilder("找到 ").append(memories.size()).append(" 条相关记忆:\n\n");
            sb.append(memoryManager.formatAsEvidenceLedger(memories));

            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to query memory (depth={}): {}", mode, e.getMessage(), e);
            return "查询记忆时出错: " + e.getMessage();
        }
    }

    /** limit 只在 1-20 之间取值，其余一律回落档位默认值（口径与改动前逐个工具里写的一致）。 */
    private int boundedLimit(Integer limit, int fallback) {
        return (limit == null || limit <= 0 || limit > 20) ? fallback : limit;
    }

    /** hybrid / deep 两档的平铺输出（形状与改动前 search_knowledge_base / deep_search 一致）。 */
    private String formatFlatList(String header, List<MemoryEntry> found, Long projectId,
                                  String scope, Long sourceFileId, int valueCap, boolean markProtected) {
        List<MemoryEntry> results = withScopedMemories(found, projectId, scope, sourceFileId);
        if (results.isEmpty()) {
            return "未在项目记忆中找到相关信息。可以换一组关键词，或用 save_memory 保存新信息。";
        }
        StringBuilder sb = new StringBuilder(header).append(results.size()).append(" 条):\n\n");
        int index = 1;
        for (MemoryEntry mem : results) {
            sb.append(index++).append(". ");
            sb.append("[").append(mem.getMemoryType().toUpperCase()).append("] ");
            if (mem.getMemoryKey() != null) {
                sb.append(markProtected ? "**" + mem.getMemoryKey() + "**" : mem.getMemoryKey()).append(": ");
            }
            String value = mem.getMemoryValue();
            if (value != null && value.length() > valueCap) {
                value = value.substring(0, valueCap) + "...";
            }
            sb.append(value);
            if (markProtected && Boolean.TRUE.equals(mem.getIsProtected())) {
                sb.append(" [受保护]");
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 把"确定性按 scope 取值"的结果并入检索算法（关键词/RRF/Agentic）返回的结果，按 id 去重、
     * scope 命中的排在前面。
     *
     * <p>审计条目：query_memory / search_knowledge_base / deep_search 完全不认 save_memory 存下的
     * scope——file/conversation 作用域的记忆只能靠检索算法"运气好"才捞得到，
     * 不是保存时承诺的"绑定到这个文件/这次对话就一定找得回来"。这里不改动任何一条检索算法本身
     * （关键词 LIKE / RRF 混合 / Agentic 多轮都不动），只是在返回前，若调用方明确给了 scope，
     * 额外做一次按 scope 的确定性查找并合并进来——保证"明确要哪个作用域"时百分之百找得到，
     * 检索算法继续负责"泛泛地找相关内容"这一半职责。
     */
    private List<MemoryEntry> withScopedMemories(List<MemoryEntry> algorithmic, Long projectId,
                                                 String scope, Long sourceFileId) {
        if (scope == null || scope.isBlank()) {
            return algorithmic;
        }
        List<MemoryEntry> scoped;
        String normalized = scope.trim().toLowerCase();
        if (MemoryEntry.MemoryScope.FILE.equals(normalized)) {
            scoped = memoryManager.retrieveFileMemories(projectId, sourceFileId);
        } else if (MemoryEntry.MemoryScope.CONVERSATION.equals(normalized)) {
            scoped = memoryManager.retrieveConversationMemories(projectId, ProjectContextHolder.getConversationId());
        } else {
            // project/user/global：现有算法已经是按 projectId 全量检索，不额外加一条确定性通路
            return algorithmic;
        }
        if (scoped.isEmpty()) {
            return algorithmic;
        }
        java.util.LinkedHashMap<Long, MemoryEntry> merged = new java.util.LinkedHashMap<>();
        for (MemoryEntry m : scoped) {
            merged.put(m.getId(), m);
        }
        for (MemoryEntry m : algorithmic) {
            merged.putIfAbsent(m.getId(), m);
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /**
     * 获取项目核心信息
     */
    @Tool("获取当前项目的核心信息，包括项目类型、交易结构、当事人、关键日期等。")
    public String get_project_context() {
        log.info("Tool: get_project_context called");
        
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        
        if (projectId == null) {
            return "错误：无法获取当前项目ID。";
        }
        
        try {
            Optional<ProjectMemory> pmOpt = memoryManager.getProjectMemory(projectId);
            
            if (pmOpt.isEmpty()) {
                return "项目记忆尚未建立。系统会在对话过程中自动提取并保存项目信息，您也可以使用 update_project_info 手动更新。";
            }
            
            ProjectMemory pm = pmOpt.get();
            StringBuilder sb = new StringBuilder("# 项目核心信息\n\n");
            sb.append(pm.toCoreContext());
            
            // 添加统计信息
            Map<String, Object> stats = memoryManager.getMemoryStats(projectId);
            sb.append("\n## 记忆统计\n");
            sb.append("- 总记忆条目: ").append(stats.get("totalMemories")).append("\n");
            
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to get project context: {}", e.getMessage(), e);
            return "获取项目信息时出错: " + e.getMessage();
        }
    }

    /**
     * 更新项目信息
     */
    @Tool("更新项目的核心信息，如项目名称、交易金额、关键日期等。")
    public String update_project_info(
            @P("要更新的字段: projectName/projectType/listedCompany/targetCompany/transactionStructure/transactionAmount") String field,
            @P("新的值") String value
    ) {
        log.info("Tool: update_project_info called field={}, value={}", field, value);
        
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        
        if (projectId == null) {
            return "错误：无法获取当前项目ID。";
        }
        
        try {
            memoryManager.updateProjectField(projectId, field, value);
            return String.format("✓ 项目信息已更新\n- 字段: %s\n- 新值: %s", field, value);
        } catch (Exception e) {
            log.error("Failed to update project info: {}", e.getMessage(), e);
            return "更新项目信息时出错: " + e.getMessage();
        }
    }

    /**
     * 兼容入口：原「智能混合搜索知识库（RRF 融合）」。
     *
     * <p>{@code offerToModel = false}——规格不下发，模型看不见即不会在三个同义工具之间摇摆
     * （审计 A13）。老会话回放 / XML 兜底路径调到时仍然照常执行，等价于
     * {@code query_memory(query, depth="hybrid")}，并在结果末尾附一句指路。
     */
    @ToolMeta(offerToModel = false)
    @Tool("[已并入 query_memory(depth=\"hybrid\")] 兼容入口，请改用 query_memory。")
    public String search_knowledge_base(
            @P("搜索查询，描述你想查找的信息") String query,
            @P("返回结果数量，默认5") int limit,
            @P("按作用域精确定位(可选): file(需配 sourceFileId)/conversation(取当前对话)，"
                    + "不传则和此前一样只按语义相关性在全项目范围检索") String scope,
            @P("来源文件ID(可选)，scope=file 时必填") Long sourceFileId
    ) {
        log.info("Tool: search_knowledge_base (compat -> query_memory depth=hybrid) query='{}', limit={}, scope='{}'",
                query, limit, scope);
        return retrieveWithDepth(query, null, scope, sourceFileId, "hybrid", limit)
                + MERGED_INTO_QUERY_MEMORY;
    }

    /**
     * 兼容入口：原「Agentic 深度搜索（多轮召回）」。
     *
     * <p>同上，等价于 {@code query_memory(query, depth="deep")}。这一档会额外起一次辅助模型
     * 做查询扩展，把它做成参数而不是一个单独的工具，正是为了让「要不要多花这一次调用」
     * 成为模型能明确判断的事。
     */
    @ToolMeta(offerToModel = false)
    @Tool("[已并入 query_memory(depth=\"deep\")] 兼容入口，请改用 query_memory。")
    public String deep_search(
            @P("搜索查询，描述你想查找的信息") String query,
            @P("返回结果数量，默认10") int limit,
            @P("按作用域精确定位(可选): file(需配 sourceFileId)/conversation(取当前对话)，"
                    + "不传则和此前一样只按多轮召回在全项目范围检索") String scope,
            @P("来源文件ID(可选)，scope=file 时必填") Long sourceFileId
    ) {
        log.info("Tool: deep_search (compat -> query_memory depth=deep) query='{}', limit={}, scope='{}'",
                query, limit, scope);
        return retrieveWithDepth(query, null, scope, sourceFileId, "deep", limit)
                + MERGED_INTO_QUERY_MEMORY;
    }

    /**
     * 获取对话摘要
     */
    @Tool("获取当前对话的历史摘要，了解之前讨论的要点和结论。")
    public String get_conversation_summary() {
        log.info("Tool: get_conversation_summary called");
        
        String conversationId = ProjectContextHolder.getConversationId();
        
        if (conversationId == null || conversationId.isEmpty()) {
            return "错误：无法获取当前对话ID。";
        }
        
        try {
            Optional<ConversationSummary> summaryOpt = memoryManager.getConversationSummary(conversationId);
            
            if (summaryOpt.isEmpty()) {
                return "当前对话暂无摘要。系统会在对话累积足够消息后自动生成摘要。";
            }
            
            ConversationSummary summary = summaryOpt.get();
            StringBuilder sb = new StringBuilder("# 对话摘要\n\n");
            sb.append(summary.getSummaryText()).append("\n");
            
            if (summary.getKeyPoints() != null && !summary.getKeyPoints().isEmpty()) {
                sb.append("\n## 关键要点\n");
                for (String point : summary.getKeyPoints()) {
                    sb.append("- ").append(point).append("\n");
                }
            }
            
            if (summary.getLegalReferences() != null && !summary.getLegalReferences().isEmpty()) {
                sb.append("\n## 法律引用\n");
                for (String ref : summary.getLegalReferences()) {
                    sb.append("- ").append(ref).append("\n");
                }
            }
            
            if (summary.getPendingTasks() != null && !summary.getPendingTasks().isEmpty()) {
                sb.append("\n## 待办事项\n");
                for (String task : summary.getPendingTasks()) {
                    sb.append("- [ ] ").append(task).append("\n");
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to get conversation summary: {}", e.getMessage(), e);
            return "获取对话摘要时出错: " + e.getMessage();
        }
    }

    @Tool("列出 Markdown 长期记忆空间中的文件。scope 只能是 user、project、team 或 firm；空间身份由当前登录用户和项目自动确定。")
    @ToolMeta(displayName = "列出记忆文件", category = "memory")
    public String memory_list(@P("记忆范围: user/project/team/firm") String scope) {
        try {
            MemorySpaceView space = resolveDocumentSpace(scope);
            List<MemoryFileView> files = memoryDocumentService.listFiles(currentUser(), space.id());
            if (files.isEmpty()) return "该记忆空间暂无文件。";
            StringBuilder result = new StringBuilder("# ").append(space.label()).append("\n");
            for (MemoryFileView file : files) {
                result.append("- ").append(file.path()).append(" | ")
                        .append(file.title()).append(" | revision=").append(file.revision()).append("\n");
            }
            return result.toString();
        } catch (Exception e) {
            return documentError("列出记忆文件", e);
        }
    }

    @Tool("读取一个 Markdown 长期记忆文件。scope 只能是 user、project、team 或 firm；先用 memory_list 获取路径。")
    @ToolMeta(displayName = "读取记忆文件", category = "memory")
    public String memory_read(@P("记忆范围: user/project/team/firm") String scope,
                              @P("相对 Markdown 路径，例如 remember.md 或 topics/style.md") String path) {
        try {
            MemorySpaceView space = resolveDocumentSpace(scope);
            MemoryFileView file = memoryDocumentService.read(currentUser(), space.id(), path);
            return "path=" + file.path() + " revision=" + file.revision() + "\n\n" + file.content();
        } catch (Exception e) {
            return documentError("读取记忆文件", e);
        }
    }

    @Tool("在一个 Markdown 长期记忆空间内搜索标题、路径和正文。scope 只能是 user、project、team 或 firm。")
    @ToolMeta(displayName = "搜索记忆", category = "memory")
    public String memory_search(@P("记忆范围: user/project/team/firm") String scope,
                                @P("搜索词") String query) {
        try {
            MemorySpaceView space = resolveDocumentSpace(scope);
            List<MemoryFileView> files = memoryDocumentService.search(currentUser(), space.id(), query, 10);
            if (files.isEmpty()) return "未找到相关 Markdown 记忆。";
            StringBuilder result = new StringBuilder();
            for (MemoryFileView file : files) {
                result.append("- ").append(file.path()).append(" | ")
                        .append(file.title()).append(" | revision=").append(file.revision()).append("\n");
            }
            return result.toString();
        } catch (Exception e) {
            return documentError("搜索记忆", e);
        }
    }

    @Tool("创建或整篇更新 Markdown 长期记忆。更新前必须 memory_read 并传回读取到的 revision；新文件 expectedRevision 传 0。")
    @ToolMeta(displayName = "写入记忆文件", category = "memory")
    public String memory_write(@P("记忆范围: user/project/team/firm") String scope,
                               @P("相对 Markdown 路径") String path,
                               @P("完整 Markdown 正文") String content,
                               @P("乐观锁版本；新文件为 0，更新为 memory_read 返回的 revision") long expectedRevision) {
        try {
            MemorySpaceView space = resolveDocumentSpace(scope);
            requireWritable(space);
            MemoryFileView file = memoryDocumentService.write(currentUser(), space.id(), path, content, expectedRevision);
            return "✓ 记忆已保存: " + file.path() + " revision=" + file.revision();
        } catch (Exception e) {
            return documentError("写入记忆文件", e);
        }
    }

    @Tool("对 Markdown 长期记忆做一次精确文本替换。oldText 必须在当前正文中恰好出现一次，并需传入当前 revision。")
    @ToolMeta(displayName = "编辑记忆文件", category = "memory")
    public String memory_edit(@P("记忆范围: user/project/team/firm") String scope,
                              @P("相对 Markdown 路径") String path,
                              @P("正文中恰好出现一次的原文本") String oldText,
                              @P("替换后的新文本") String newText,
                              @P("memory_read 返回的当前 revision") long expectedRevision) {
        try {
            if (oldText == null || oldText.isEmpty()) return "编辑记忆文件失败：oldText 不能为空。";
            MemorySpaceView space = resolveDocumentSpace(scope);
            requireWritable(space);
            MemoryFileView current = memoryDocumentService.read(currentUser(), space.id(), path);
            if (current.revision() != expectedRevision) {
                return "编辑记忆文件失败：revision 已变化，请重新读取后重试。";
            }
            int first = current.content().indexOf(oldText);
            int second = first < 0 ? -1 : current.content().indexOf(oldText, first + oldText.length());
            if (first < 0 || second >= 0) return "编辑记忆文件失败：oldText 必须在当前正文中恰好出现一次。";
            String replacement = newText == null ? "" : newText;
            String content = current.content().substring(0, first) + replacement
                    + current.content().substring(first + oldText.length());
            MemoryFileView updated = memoryDocumentService.write(
                    currentUser(), space.id(), path, content, expectedRevision);
            return "✓ 记忆已编辑: " + updated.path() + " revision=" + updated.revision();
        } catch (Exception e) {
            return documentError("编辑记忆文件", e);
        }
    }

    @Tool("删除一个 Markdown 长期记忆主题文件。remember.md 不能删除；必须传入当前 revision。")
    @ToolMeta(displayName = "删除记忆文件", category = "memory")
    public String memory_delete(@P("记忆范围: user/project/team/firm") String scope,
                                @P("相对 Markdown 路径") String path,
                                @P("memory_read 返回的当前 revision") long expectedRevision) {
        try {
            MemorySpaceView space = resolveDocumentSpace(scope);
            requireWritable(space);
            memoryDocumentService.delete(currentUser(), space.id(), path, expectedRevision);
            return "✓ 记忆已删除: " + path;
        } catch (Exception e) {
            return documentError("删除记忆文件", e);
        }
    }

    private MemorySpaceView resolveDocumentSpace(String scope) {
        if (memoryDocumentService == null) throw new IllegalStateException("Markdown 记忆服务未启用");
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if (!List.of("user", "project", "team", "firm").contains(normalized)) {
            throw new IllegalArgumentException("scope 必须是 user、project、team 或 firm");
        }
        return memoryDocumentService.listSpaces(currentUser(), ProjectContextHolder.getProjectIdAsLong()).stream()
                .filter(space -> normalized.equals(space.scope())).findFirst()
                .map(space -> {
                    if (!space.available() || !space.readable() || space.id() == null) {
                        throw new IllegalStateException(space.reason() == null ? "该记忆空间暂不可用" : space.reason());
                    }
                    return space;
                }).orElseThrow(() -> new IllegalStateException("该记忆空间暂不可用"));
    }

    private static void requireWritable(MemorySpaceView space) {
        if (!space.writable()) throw new IllegalStateException("当前账号对该记忆空间只有读取权限");
    }

    private static Long currentUser() {
        Long userId = ProjectContextHolder.getUserId();
        if (userId == null) throw new IllegalStateException("无法获取当前用户ID");
        return userId;
    }

    private static String documentError(String action, Exception e) {
        return action + "失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    void setMemoryDocumentServiceForTest(MemoryDocumentService service) {
        this.memoryDocumentService = service;
    }

    /**
     * 验证记忆类型是否有效
     */
    private boolean isValidMemoryType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.equals("decision") || t.equals("conclusion") || 
               t.equals("fact") || t.equals("reference") || t.equals("preference");
    }
}
