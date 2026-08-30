package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.ProjectAiMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectAiMessageService {

    private final ProjectAiMessageRepository repository;
    private final com.checkba.service.ai.ConversationIssuanceService conversationIssuanceService;
    /** 概览页会话列表的运行状态来源：读表不读 AgentRunStateService 的内存 Map。 */
    private final com.checkba.repository.AgentRunRecordRepository agentRunRecordRepository;
    /** 概览页会话列表的发起人显示名。 */
    private final com.checkba.repository.UserRepository userRepository;

    /**
     * 插件对话镜像（dev-board#298）旁路挂钩。可选注入（field 注入而非构造器参数）：
     * 五处手工 {@code new ProjectAiMessageService(...)} 的测试不受牵连，null 即整条旁路关闭。
     * 每个落库口保存后调 {@link #mirror(ProjectAiMessage)}——绑定项目的消息进 outbox。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.service.addin.AddinConvSyncService addinConvSyncService;

    private void mirror(ProjectAiMessage msg) {
        if (addinConvSyncService != null) {
            addinConvSyncService.record(msg);
        }
    }

    public void saveUserAndAssistantMessage(String projectIdStr, Long userId, String conversationId, String userContent, String assistantContent) {
        if (projectIdStr == null) {
            return;
        }
        Long projectId;
        try {
            projectId = Long.parseLong(projectIdStr);
        } catch (NumberFormatException e) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();

        ProjectAiMessage userMsg = new ProjectAiMessage();
        userMsg.setProjectId(projectId);
        userMsg.setUserId(userId);
        userMsg.setRole("USER");
        userMsg.setContent(userContent);
        userMsg.setConversationId(conversationId);
        userMsg.setCreatedAt(now);
        repository.save(userMsg);

        ProjectAiMessage aiMsg = new ProjectAiMessage();
        aiMsg.setProjectId(projectId);
        aiMsg.setUserId(userId);
        aiMsg.setRole("ASSISTANT");
        aiMsg.setContent(assistantContent);
        aiMsg.setConversationId(conversationId);
        aiMsg.setCreatedAt(LocalDateTime.now());
        repository.save(aiMsg);
        mirror(userMsg);
        mirror(aiMsg);
    }

    // Deprecated or Legacy support
    public void saveUserAndAssistantMessage(String projectIdStr, Long userId, String userContent, String assistantContent) {
        saveUserAndAssistantMessage(projectIdStr, userId, null, userContent, assistantContent);
    }

    /**
     * Save a single message (user OR assistant) to the database.
     * Used for streaming scenarios where assistant response comes after user message.
     * 总是插入新行；同一轮次内 ASSISTANT 消息的增量更新请用 {@link #upsertAssistantMessage}。
     */
    public void saveMessage(String projectIdStr, Long userId, String conversationId, String role, String content) {
        saveMessage(projectIdStr, userId, conversationId, role, content, null);
    }

    /**
     * 带「显示内容」的保存（契约 D：发送内容 ≠ 显示内容）。
     *
     * <p>{@code content} 是模型看的那份（细节要给全），{@code displayContent} 是用户看的那份
     * （一句人话）。displayContent 为空/空白一律落 null——「缺省 = 与今天行为完全一致」
     * 是这条通道的存量兼容前提，不许写空串占位，否则前端 {@code displayContent || content}
     * 的回退判断在不同客户端上会有分歧。
     *
     * <p>模型侧读取一律走 content（见 ProjectAiMessage#displayContent 的红线说明）。
     */
    public void saveMessage(String projectIdStr, Long userId, String conversationId, String role,
                            String content, String displayContent) {
        if (projectIdStr == null || role == null) {
            return;
        }
        Long projectId;
        try {
            projectId = Long.parseLong(projectIdStr);
        } catch (NumberFormatException e) {
            return;
        }
        ProjectAiMessage msg = new ProjectAiMessage();
        msg.setProjectId(projectId);
        msg.setUserId(userId);
        msg.setRole(role.toUpperCase());
        msg.setContent(content);
        msg.setDisplayContent(displayContent == null || displayContent.isBlank() ? null : displayContent);
        msg.setConversationId(conversationId);
        msg.setCreatedAt(java.time.LocalDateTime.now());
        repository.save(msg);
        mirror(msg);
    }

    /**
     * 保存或更新本轮 ASSISTANT 消息。
     * 编排器按对话轮次跟踪消息 ID：同一轮内的增量保存/最终保存更新同一行，
     * 新的一轮传 null 插入新行。
     * （修复历史丢消息：旧实现按"会话最后一条 ASSISTANT 距今 30 秒内则更新"判断，
     * 没有轮次概念，用户两轮提问间隔小于 30 秒时，第二轮回复会覆盖第一轮回复。）
     *
     * @param existingMessageId 本轮已保存过的消息 ID；null 表示本轮首次保存
     * @return 保存后的消息 ID（供本轮后续增量保存复用）；参数非法时返回 null
     */
    public Long upsertAssistantMessage(String projectIdStr, Long userId, String conversationId, Long existingMessageId, String content) {
        if (projectIdStr == null) {
            return null;
        }
        Long projectId;
        try {
            projectId = Long.parseLong(projectIdStr);
        } catch (NumberFormatException e) {
            return null;
        }

        if (existingMessageId != null) {
            java.util.Optional<ProjectAiMessage> existingOpt = repository.findById(existingMessageId);
            if (existingOpt.isPresent()) {
                ProjectAiMessage existing = existingOpt.get();
                existing.setContent(content);
                repository.save(existing);
                mirror(existing);
                return existing.getId();
            }
        }

        ProjectAiMessage msg = new ProjectAiMessage();
        msg.setProjectId(projectId);
        msg.setUserId(userId);
        msg.setRole("ASSISTANT");
        msg.setContent(content);
        msg.setConversationId(conversationId);
        msg.setCreatedAt(java.time.LocalDateTime.now());
        repository.save(msg);
        mirror(msg);
        return msg.getId();
    }

    public List<ProjectAiMessage> listByProject(Long projectId) {
        return repository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    public List<ProjectAiMessage> listByProjectAndUser(Long projectId, Long userId) {
        if (userId == null) {
            return listByProject(projectId);
        }
        return repository.findByProjectIdAndUserIdOrderByCreatedAtAsc(projectId, userId);
    }

    public List<ProjectAiMessage> listByConversationId(String conversationId) {
        return repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * 是否为插件镜像会话（dev-board#298）：首条消息带 sourceChannel。
     * 镜像会话在桌面端只读——插件那头还在续写同一条时间线，桌面直接续写会双头交错；
     * 续聊走 {@link #forkConversation}。云端原生会话的 sourceChannel 恒为 null，不受影响。
     */
    public boolean isMirroredConversation(String conversationId) {
        if (conversationId == null) return false;
        return repository.findFirstByConversationId(conversationId)
                .map(m -> m.getSourceChannel() != null && !m.getSourceChannel().isBlank())
                .orElse(false);
    }

    /** 校验会话是否属于该用户（用于回滚等破坏性操作的越权防护），据首条消息的 userId 判定。 */
    public boolean isConversationOwnedBy(String conversationId, Long userId) {
        if (userId == null) return false;
        return repository.findFirstByConversationId(conversationId)
                .map(m -> userId.equals(m.getUserId()))
                .orElse(false);
    }

    /**
     * 会话是否可被该用户使用：已归属于他，或还没有任何消息（新会话，尚无主）。
     *
     * 前端进入项目就先生成 conversationId 并拉历史/开流，此时一条消息都还没落库，
     * 用 isConversationOwnedBy 判会得到 false——那是「归属判定」，不是「可用判定」，
     * 两者混用会把每个新会话都挡成 403（实测：一进项目 AI 面板就报无权）。
     * 读写类接口一律用本方法；只有明确的破坏性操作（如回滚他人会话）才用严格归属。
     */
    public boolean canUseConversation(String conversationId, Long userId) {
        if (userId == null || conversationId == null) return false;
        // 服务端签发登记优先于「空会话任何人可用」：签发给谁就归谁，
        // 首条消息落库前的抢占窗口由此关闭（2026-08 安全审计遗留项）。
        Long registeredOwner = conversationIssuanceService.ownerOf(conversationId);
        if (registeredOwner != null && !registeredOwner.equals(userId)) return false;
        return repository.findFirstByConversationId(conversationId)
                .map(m -> userId.equals(m.getUserId()))
                // 无消息的新会话：已登记（归属相符）放行；未登记时看强制开关——
                // 官方云（conversation-issuance-required=true 且非 local-mode）必须先签发，
                // 默认配置/桌面单机维持现状（客户端自造 ID 仍可用）。
                .orElseGet(() -> registeredOwner != null || !conversationIssuanceService.enforceIssuance());
    }

    public List<java.util.Map<String, Object>> listConversations(Long projectId, Long userId) {
        List<Object[]> results = repository.findConversationSummaries(projectId, userId);
        return results.stream()
                .filter(row -> row[0] != null) // Filter out items with null conversationId
                .map(row -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("conversationId", row[0]);
                    map.put("updatedAt", row[1]);
                    String lastMessage = row[2] != null ? row[2].toString() : "";
                    String storedTitle = row.length > 3 && row[3] != null ? row[3].toString() : null;
                    String firstUserMessage = row.length > 4 && row[4] != null ? row[4].toString() : "";
                    // 优先使用 LLM 生成的 conversationTitle，fallback 到 cleanTitle
                    map.put("title", storedTitle != null && !storedTitle.isBlank() ? storedTitle : cleanTitle(lastMessage));
                    // 使用 extractPreview 提取有意义的预览内容，若为空则回退到用户第一条消息
                    String preview = extractPreview(lastMessage);
                    if (preview.isEmpty() || preview.length() < 5) {
                        // 回退到用户第一条消息（清理后）
                        preview = truncatePreview(firstUserMessage.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim());
                    }
                    map.put("lastMessage", preview);
                    // 来源通道（首条消息的）：镜像导入的会话非空，前端据此渲染角标 + 只读态
                    map.put("sourceChannel", row.length > 5 && row[5] != null ? row[5].toString() : null);
                    return map;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 插件对话镜像导入（dev-board#298，桌面侧）：按 (conversationId, sourceMessageId) 幂等 upsert。
     *
     * <p>三条不变式：content 空白直接跳过（langchain4j 对空白消息抛异常，脏数据能报废整条会话）；
     * role 归一大写且只认 USER/ASSISTANT（其余值上下文组装会静默丢弃，不如导入时就拒）；
     * createdAt 必须严格递增——历史回放只按 created_at ASC 排序、没有 id tiebreaker，
     * 同刻多条的顺序是未定义的，这里以「同会话现存最大时间戳 + 1ms」为下限逐条钳。
     *
     * @return 落库的行；跳过（空白/坏 role）时返回 null
     */
    public ProjectAiMessage importExternalMessage(Long projectId, Long userId, String conversationId,
                                                  String role, String content, String displayContent,
                                                  String sourceChannel, Long sourceMessageId,
                                                  LocalDateTime originalCreatedAt) {
        if (projectId == null || conversationId == null || sourceMessageId == null) return null;
        if (content == null || content.isBlank()) return null;
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        if (!"USER".equals(normalizedRole) && !"ASSISTANT".equals(normalizedRole)) return null;

        java.util.Optional<ProjectAiMessage> existing =
                repository.findByConversationIdAndSourceMessageId(conversationId, sourceMessageId);
        if (existing.isPresent()) {
            ProjectAiMessage row = existing.get();
            row.setContent(content);
            row.setDisplayContent(displayContent == null || displayContent.isBlank() ? null : displayContent);
            return repository.save(row);
        }

        LocalDateTime createdAt = originalCreatedAt != null ? originalCreatedAt : LocalDateTime.now();
        LocalDateTime maxExisting = repository.maxCreatedAtByConversationId(conversationId);
        if (maxExisting != null && !createdAt.isAfter(maxExisting)) {
            createdAt = maxExisting.plusNanos(1_000_000);
        }

        ProjectAiMessage msg = new ProjectAiMessage();
        msg.setProjectId(projectId);
        msg.setUserId(userId);
        msg.setRole(normalizedRole);
        msg.setContent(content);
        msg.setDisplayContent(displayContent == null || displayContent.isBlank() ? null : displayContent);
        msg.setConversationId(conversationId);
        msg.setSourceChannel(sourceChannel);
        msg.setSourceMessageId(sourceMessageId);
        msg.setCreatedAt(createdAt);
        return repository.save(msg);
    }

    /**
     * fork-from-here（dev-board#298）：把整条会话的消息复制成一条新的本地会话继续聊。
     * 镜像导入的会话在桌面端只读（插件那头还在续写同一条，双头写会让下次同步交错成一锅粥），
     * 想续聊就走这条——分叉是显式动作，原件不被污染。
     *
     * <p>复制行保留原始 createdAt（回放顺序不变）；sourceChannel/sourceMessageId 置空
     * （分叉出来的是普通本地会话，可写、不再接收镜像更新）；userId 改成发起 fork 的用户。
     *
     * @return 新会话 id（conv-毫秒，桌面自造格式）
     */
    @org.springframework.transaction.annotation.Transactional
    public String forkConversation(String conversationId, Long userId) {
        List<ProjectAiMessage> source = repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (source.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("会话不存在或为空", "Conversation not found or empty"));
        }
        String newConversationId = "conv-" + System.currentTimeMillis();
        String baseTitle = null;
        for (ProjectAiMessage m : source) {
            if (baseTitle == null && m.getConversationTitle() != null && !m.getConversationTitle().isBlank()) {
                baseTitle = m.getConversationTitle();
            }
        }
        if (baseTitle == null || baseTitle.isBlank()) {
            baseTitle = cleanTitle(source.get(source.size() - 1).getContent());
        }
        String suffix = LangText.of("（分支）", " (branch)");
        String forkTitle = baseTitle + suffix;
        if (forkTitle.length() > 100) {
            forkTitle = baseTitle.substring(0, Math.max(0, 100 - suffix.length())) + suffix;
        }
        boolean first = true;
        for (ProjectAiMessage m : source) {
            ProjectAiMessage copy = new ProjectAiMessage();
            copy.setProjectId(m.getProjectId());
            copy.setUserId(userId);
            copy.setRole(m.getRole());
            copy.setContent(m.getContent());
            copy.setDisplayContent(m.getDisplayContent());
            copy.setConversationId(newConversationId);
            copy.setCreatedAt(m.getCreatedAt());
            if (first) {
                copy.setConversationTitle(forkTitle);
                first = false;
            }
            repository.save(copy);
        }
        return newConversationId;
    }

    /**
     * Clean conversation title by stripping common XML tags like <thinking>, <process>, etc.
     */
    private String cleanTitle(String rawTitle) {
        // 读时兜底按当前应用语言渲染（存量库里也可能存着另一种语言的字面量，不迁移）
        if (rawTitle == null || rawTitle.isBlank()) {
            return LangText.of("新对话", "New chat");
        }
        // Remove common XML tags
        String cleaned = rawTitle
            .replaceAll("(?s)<thinking>.*?</thinking>", "")
            .replaceAll("(?s)<process[^>]*>.*?</process>", "")
            .replaceAll("(?s)<step>.*?</step>", "")
            .replaceAll("(?s)<tool_code>.*?</tool_code>", "")
            .replaceAll("(?s)<tool_output>.*?</tool_output>", "")
            .replaceAll("(?s)<artifact[^>]*>.*?</artifact>", "")
            .replaceAll("(?s)<final>.*?</final>", "")
            .replaceAll("<[^>]+>", "") // Remove any remaining tags
            .trim();
        
        // Truncate to reasonable length for display
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100) + "...";
        }
        
        return cleaned.isEmpty() ? LangText.of("新对话", "New chat") : cleaned;
    }

    /**
     * 提取有意义的预览内容，优先从 <final> 标签中提取，
     * 过滤掉 tool_code, tool_output, thinking, process 等技术性标签内容
     */
    private String extractPreview(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        
        // 1. 优先提取 <final> 标签内容（LLM的最终输出）
        java.util.regex.Pattern finalPattern = java.util.regex.Pattern.compile("<final>([\\s\\S]*?)</final>");
        java.util.regex.Matcher finalMatcher = finalPattern.matcher(rawContent);
        if (finalMatcher.find()) {
            String finalContent = finalMatcher.group(1).trim();
            // 清理 final 内容中的 markdown 和多余空白
            finalContent = finalContent
                .replaceAll("```[a-z]*\\n?", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("\\s+", " ")
                .trim();
            if (!finalContent.isEmpty() && finalContent.length() > 3) {
                return truncatePreview(finalContent);
            }
        }
        
        // 2. 移除所有技术性标签及其内容
        String cleaned = rawContent
            .replaceAll("(?s)<thinking>.*?</thinking>", "")
            .replaceAll("(?s)<process[^>]*>.*?</process>", "")
            .replaceAll("(?s)<step>.*?</step>", "")
            .replaceAll("(?s)<tool_code>.*?</tool_code>", "")
            .replaceAll("(?s)<tool_output[^>]*>.*?</tool_output>", "")
            .replaceAll("(?s)<artifact[^>]*>.*?</artifact>", "")
            .replaceAll("(?s)<root_bubble[^>]*>.*?</root_bubble>", "")
            .replaceAll("<[^>]+>", "") // 移除任何剩余的标签
            .replaceAll("```[a-z]*\\n?", "") // 移除代码块标记
            .replaceAll("\\*\\*", "") // 移除加粗标记
            .replaceAll("\\s+", " ") // 合并多余空白
            .trim();
        
        // 3. 过滤明显是代码的内容（以 import, def, function, class 等开头）
        if (cleaned.matches("^(import |def |function |class |const |let |var |public |private ).*")) {
            return "";
        }
        
        return truncatePreview(cleaned);
    }

    /**
     * 截断预览内容到合理长度
     */
    private String truncatePreview(String content) {
        if (content == null) return "";
        // 取前80个字符，找到自然断点（句号、逗号、空格等）
        if (content.length() <= 80) {
            return content;
        }
        String truncated = content.substring(0, 80);
        // 尝试在标点或空格处截断
        int lastBreak = Math.max(
            truncated.lastIndexOf('。'),
            Math.max(truncated.lastIndexOf('，'), 
                Math.max(truncated.lastIndexOf(' '), truncated.lastIndexOf('、')))
        );
        if (lastBreak > 40) {
            return truncated.substring(0, lastBreak + 1) + "...";
        }
        return truncated + "...";
    }

    /**
     * 调用 LLM 生成对话标题（基于用户第一条消息）
     */
    public String generateConversationTitle(String userMessage, dev.langchain4j.model.chat.ChatLanguageModel model) {
        // 英文模式换英文 prompt：否则英文界面会持续产生中文标题（标题是落库文案，跟应用语言走）
        String prompt = LangText.of(
                "请为以下用户问题生成一个简短的对话标题（不超过15个字，不要标点符号，只输出标题本身）:\n",
                "Generate a short conversation title in English for the following user question "
                        + "(no more than 8 words, no punctuation, output only the title itself):\n")
                + userMessage;
        try {
            String title = model.generate(prompt);
            // Clean any XML tags or extra formatting the model might output
            title = title.replaceAll("<[^>]+>", "").replaceAll("```[a-z]*", "").trim();
            title = title.replaceAll("^[\"']+|[\"']+$", ""); // Remove quotes
            // 英文标题按词计数，30 字符会把词砍半：英文模式放宽到 60 字符（zh 行为不变）
            int cap = LangText.isEnglish() ? 60 : 30;
            if (title.length() > cap) title = title.substring(0, cap);
            return title.isEmpty() ? LangText.of("新对话", "New chat") : title;
        } catch (Exception e) {
            return LangText.of("新对话", "New chat");
        }
    }

    /**
     * 更新对话的第一条消息的标题字段
     */
    @org.springframework.transaction.annotation.Transactional
    public void updateConversationTitle(String conversationId, String title) {
        java.util.Optional<ProjectAiMessage> firstMsgOpt = repository.findFirstByConversationId(conversationId);
        if (firstMsgOpt.isPresent()) {
            ProjectAiMessage firstMsg = firstMsgOpt.get();
            firstMsg.setConversationTitle(title);
            repository.save(firstMsg);
        }
    }

    /**
     * 整会话删除（dev-board#148，Office 插件历史面板）。只删消息本体；
     * token 用量记录是计费对账凭证、文件变动清单挂在自己的服务上，均不随删。
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteConversation(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    public java.util.Optional<ProjectAiMessage> findById(Long id) {
        return repository.findById(id);
    }

    @org.springframework.transaction.annotation.Transactional
    public void truncateHistory(String conversationId, Long messageId) {
        ProjectAiMessage message = repository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        
        if (!message.getConversationId().equals(conversationId)) {
            throw new IllegalArgumentException("Message does not belong to conversation: " + conversationId);
        }

        repository.deleteByConversationIdAndCreatedAtAfter(conversationId, message.getCreatedAt());
    }

    /**
     * 项目级会话列表（概览页用）：不按 userId 过滤，这个项目的成员都看得到全部会话。
     *
     * 与 {@link #listConversations} 只有三点不同 —— 可见性口径（项目全员 vs 我自己）、
     * runStatus 来源（agent_run_record 表 vs 内存 Map）、预览回退条件（只判空串 vs
     * 还判长度不足 5，后者会把「已核对」这类合法短回复也替换掉）。
     * 标题与预览的清洗一律复用 cleanTitle / extractPreview / truncatePreview 三个私有方法：
     * 仓里已经有两套并行漂移的清洗正则（服务端一套、前端 fetchChatHistory 一套），不许出第三套。
     * 既有的 listConversations 服务 AI 面板，一行都不改。
     *
     * 只有列表层。正文一行都不下发 —— 正文层仍走 canUseConversation 判权。
     *
     * <p><b>可见性口径（spec §6.4）</b>：列表层只把标题/时间/发起人/状态授权给项目全员——
     * 不包括正文。{@code ownerUserId} 与 {@code callerUserId} 不一致的行，
     * {@code lastMessage} 恒为 null；{@code title} 只信 storedTitle，没有 storedTitle
     * 时给 cleanTitle 对空白输入返回的那个中性文案，不许像自己的会话那样用
     * cleanTitle(正文) 从别人的对话正文推标题——那等于把正文换了个字段名继续下发。
     * 自己发起的行（ownerUserId 与 callerUserId 相同）不受影响，行为与此前一致。
     *
     * @param before       游标的时间维；null 表示第一页
     * @param beforeId     游标的会话维（上一页最后一条的 conversationId）。与 before 成对使用：
     *                     只给 before 时同一时刻的另一个会话会被永久跳过
     * @param limit        期望条数，服务端钳到 1..50
     * @param callerUserId 发起本次查询的用户 —— 用来判定每一行是不是调用者自己的会话
     * @return {"conversations": [...], "nextBefore": ISO 串或 null, "nextBeforeId": 会话 id 或 null}
     */
    public java.util.Map<String, Object> listProjectConversations(Long projectId, LocalDateTime before,
                                                                 String beforeId, int limit, Long callerUserId) {
        int pageSize = Math.max(1, Math.min(50, limit));

        // limit 只能在 Java 层做：那条 JPQL 有 4 个标量子查询 + GROUP BY + HAVING，
        // 套 Pageable 会逼出手写 countQuery 或两段式。多取一条用来判有没有下一页。
        List<Object[]> rows = repository.findProjectConversationSummaries(projectId, before, beforeId).stream()
                .filter(row -> row[0] != null)
                .limit(pageSize + 1L)
                .collect(java.util.stream.Collectors.toList());
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new java.util.ArrayList<>(rows.subList(0, pageSize));
        }

        // 运行状态批量取，防 N+1。读 agent_run_record 表而不是 AgentRunStateService 的
        // 内存 Map：内存态进程重启后全为 null，概览页把历史铺开时会整片显示无状态。
        java.util.Map<String, String> statusByConversation = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            java.util.List<String> conversationIds = rows.stream()
                    .map(row -> (String) row[0])
                    .collect(java.util.stream.Collectors.toList());
            for (com.checkba.model.entity.AgentRunRecord record
                    : agentRunRecordRepository.findByConversationIdIn(conversationIds)) {
                if (record.getConversationId() != null) {
                    statusByConversation.put(record.getConversationId(), record.getStatus());
                }
            }
        }

        // 发起人显示名批量取，同样防 N+1。
        java.util.Map<Long, String> nameByUserId = new java.util.HashMap<>();
        java.util.Set<Long> ownerIds = rows.stream()
                .map(row -> (Long) row[5])
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (!ownerIds.isEmpty()) {
            for (com.checkba.model.entity.User user : userRepository.findAllById(ownerIds)) {
                String name = user.getDisplayName();
                if (name == null || name.isBlank()) {
                    name = user.getUsername();
                }
                if (name != null) {
                    nameByUserId.put(user.getId(), name);
                }
            }
        }

        java.util.List<java.util.Map<String, Object>> conversations = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            String conversationId = (String) row[0];
            LocalDateTime updatedAt = (LocalDateTime) row[1];
            String lastContent = row[2] != null ? row[2].toString() : "";
            String storedTitle = row[3] != null ? row[3].toString() : null;
            String firstUserMessage = row[4] != null ? row[4].toString() : "";
            Long ownerUserId = (Long) row[5];
            boolean isOwnConversation = ownerUserId != null && ownerUserId.equals(callerUserId);

            String lastMessage = null;
            String title;
            if (isOwnConversation) {
                String preview = extractPreview(lastContent);
                if (preview.isEmpty()) {
                    // extractPreview 对以 import/def/function/class/const/let/var/public/private
                    // 开头的正文直接返回空串（本类 :275），回退到用户第一条消息。
                    // 只判空串：加「长度不足 N」会把「已核对」「好的」这类合法短回复也顶掉。
                    preview = truncatePreview(
                            firstUserMessage.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim());
                }
                lastMessage = preview;
                title = storedTitle != null && !storedTitle.isBlank() ? storedTitle : cleanTitle(lastContent);
            } else {
                // spec §6.4：列表层只把标题/时间/发起人/状态授权给项目全员，正文不在其中。
                // lastMessage 保持 null；title 只信 storedTitle，没有时给中性文案——
                // 不许像自己的会话那样用 cleanTitle(lastContent) 从别人的正文推标题，
                // 那等于把正文换个字段名继续下发（2026-08 安全审计修过的那类问题）。
                title = storedTitle != null && !storedTitle.isBlank() ? storedTitle : cleanTitle(null);
            }

            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("conversationId", conversationId);
            item.put("title", title);
            item.put("lastMessage", lastMessage);
            // ISO 串而不是原始 LocalDateTime：前端直接显示，且能原样当成下一页的 before 传回来
            // （保留纳秒精度，避免截到秒后漏掉同一秒内的另一个会话）。
            item.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
            item.put("runStatus", statusByConversation.get(conversationId));
            item.put("ownerUserId", ownerUserId);
            item.put("ownerName", ownerUserId == null ? null : nameByUserId.get(ownerUserId));
            // 来源通道（首条消息的）：镜像导入的会话非空，概览页据此渲染来源角标
            item.put("sourceChannel", row.length > 6 && row[6] != null ? row[6].toString() : null);
            conversations.add(item);
        }

        java.util.Map<String, Object> last = conversations.isEmpty()
                ? null : conversations.get(conversations.size() - 1);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("conversations", conversations);
        // 游标两维成对下发：少给 nextBeforeId 会让同一时刻的两个会话在翻页时丢一条。
        result.put("nextBefore", hasMore && last != null ? last.get("updatedAt") : null);
        result.put("nextBeforeId", hasMore && last != null ? last.get("conversationId") : null);
        return result;
    }
}


