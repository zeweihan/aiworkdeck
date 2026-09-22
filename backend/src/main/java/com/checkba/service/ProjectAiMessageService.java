// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProjectAiMessageService.class);

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

    /**
     * 消息 ↔ 附件关联（dev-board#793 K14 ④）。同 {@link #addinConvSyncService} 的理由走 field 注入：
     * 本类是 {@code @RequiredArgsConstructor}，往构造器里加参数要牵动五处手工 new 的测试。
     * 生产环境 Spring 必然注入；为 null 只会发生在没有调 {@link #setAttachmentRepositoryForTest}
     * 的手工构造里，此时整条旁路关闭（历史照常，只是没有附件 chip）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.repository.ProjectAiMessageAttachmentRepository attachmentRepository;

    void setAttachmentRepositoryForTest(com.checkba.repository.ProjectAiMessageAttachmentRepository repo) {
        this.attachmentRepository = repo;
    }

    /** 一条待落库的附件关联（{@link com.checkba.service.ai.ContextTurnSink} 的账本条目）。 */
    public record AttachmentRecord(String fileId, String name, String fileType, String kind, boolean visionUsed) {
    }

    /**
     * 把本轮的附件挂到刚落库的那条消息上。
     *
     * <p><b>失败只 log</b>：附件 chip 是锦上添花，写不进去绝不能掀翻这一轮对话
     *（同 {@code TodoListService} 写透 DB 的口径）。
     */
    public void recordAttachments(Long messageId, List<AttachmentRecord> records) {
        if (messageId == null || records == null || records.isEmpty() || attachmentRepository == null) {
            return;
        }
        try {
            List<com.checkba.model.entity.ProjectAiMessageAttachment> rows = new java.util.ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (AttachmentRecord r : records) {
                com.checkba.model.entity.ProjectAiMessageAttachment row =
                        new com.checkba.model.entity.ProjectAiMessageAttachment();
                row.setMessageId(messageId);
                row.setFileId(r.fileId());
                row.setName(r.name());
                row.setKind(r.kind());
                row.setFileType(r.fileType());
                row.setVisionUsed(r.visionUsed());
                row.setCreatedAt(now);
                rows.add(row);
            }
            attachmentRepository.saveAll(rows);
        } catch (Exception e) {
            log.warn("Failed to record {} attachment(s) for message {}", records.size(), messageId, e);
        }
    }

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
    public Long saveMessage(String projectIdStr, Long userId, String conversationId, String role, String content) {
        return saveMessage(projectIdStr, userId, conversationId, role, content, null);
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
     *
     * @return 落库后的行 id；参数非法而没落库时返回 null。
     *         调用方拿它把本轮附件挂上去（{@link #recordAttachments}）——
     *         返回值是追加的，忽略它的既有调用方行为完全不变。
     */
    public Long saveMessage(String projectIdStr, Long userId, String conversationId, String role,
                            String content, String displayContent) {
        return saveMessage(projectIdStr, userId, conversationId, role, content, displayContent, null);
    }

    /**
     * 带回退定位键的保存（dev-board#779 K1）。
     *
     * <p>{@code clientRequestId} 是客户端为这次提交生成的幂等键，USER 行必须带上——
     * 它是「回退到这条消息」唯一在消息落库前就存在的定位键（原委见
     * {@link ProjectAiMessage#getClientRequestId()}）。ASSISTANT 行不需要，传 null。
     * 空白一律落 null，与 displayContent 同口径。
     *
     * <p>这一个是真正落库的实现，另外两个重载都委托到它。
     * <b>返回行 id</b>（dev-board#793 K14 ④）：附件关联要挂在这一行上，而主键到落库这一刻才生成。
     */
    public Long saveMessage(String projectIdStr, Long userId, String conversationId, String role,
                            String content, String displayContent, String clientRequestId) {
        if (projectIdStr == null || role == null) {
            return null;
        }
        Long projectId;
        try {
            projectId = Long.parseLong(projectIdStr);
        } catch (NumberFormatException e) {
            return null;
        }
        ProjectAiMessage msg = new ProjectAiMessage();
        msg.setProjectId(projectId);
        msg.setUserId(userId);
        msg.setRole(role.toUpperCase());
        msg.setContent(content);
        msg.setDisplayContent(displayContent == null || displayContent.isBlank() ? null : displayContent);
        msg.setClientRequestId(clientRequestId == null || clientRequestId.isBlank() ? null : clientRequestId.trim());
        msg.setConversationId(conversationId);
        msg.setCreatedAt(java.time.LocalDateTime.now());
        repository.save(msg);
        mirror(msg);
        return msg.getId();
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
        List<ProjectAiMessage> messages = repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        attachAttachments(messages);
        return messages;
    }

    /**
     * 一次把整条会话的附件取回来挂到各条消息上（N+1 防护）。
     *
     * <p>失败只 log：附件 chip 没有比「历史打不开」更重要。
     */
    private void attachAttachments(List<ProjectAiMessage> messages) {
        if (attachmentRepository == null || messages == null || messages.isEmpty()) return;
        try {
            List<Long> ids = messages.stream().map(ProjectAiMessage::getId)
                    .filter(java.util.Objects::nonNull).toList();
            if (ids.isEmpty()) return;
            java.util.Map<Long, List<com.checkba.model.entity.ProjectAiMessageAttachment>> byMessage =
                    new java.util.HashMap<>();
            for (com.checkba.model.entity.ProjectAiMessageAttachment a
                    : attachmentRepository.findByMessageIdInOrderByIdAsc(ids)) {
                byMessage.computeIfAbsent(a.getMessageId(), k -> new java.util.ArrayList<>()).add(a);
            }
            if (byMessage.isEmpty()) return;
            for (ProjectAiMessage m : messages) {
                List<com.checkba.model.entity.ProjectAiMessageAttachment> mine = byMessage.get(m.getId());
                if (mine != null) m.setAttachments(mine);
            }
        } catch (Exception e) {
            log.warn("Failed to load attachments for conversation history", e);
        }
    }

    /**
     * 会话消息条数（dev-board#729 ⑤）。判「是不是会话首轮」用它，不要用
     * {@code listByConversationId(...).size()}——那会把整条会话的正文与执行日志全读出来，
     * 而这个判断只需要一个数字，且它就在用户等待首 token 的关键路径上。
     */
    public long countByConversationId(String conversationId) {
        return repository.countByConversationId(conversationId);
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

    /**
     * AI 面板的历史会话列表（user-scoped）。
     *
     * <p>置顶项排在最前（dev-board#796）：排序用<b>稳定</b>排序，所以两组内部仍是
     * 仓储给的「最后活跃时间倒序」。置顶只影响这一个顺序，不影响任何别的行为。
     */
    public List<java.util.Map<String, Object>> listConversations(Long projectId, Long userId) {
        List<Object[]> results = repository.findConversationSummaries(projectId, userId);
        // 「分支自 <父标题>」角标的素材（dev-board#779 K18）：父会话不一定在这一页里，
        // 逐条查是 N+1，所以先把这一页引用到的父会话 id 收齐，一次查回标题。
        java.util.Set<String> parentIds = results.stream()
                .filter(row -> row[0] != null && row.length > 7 && row[7] != null)
                .map(row -> row[7].toString())
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, String> parentTitles = resolveConversationTitles(parentIds);
        List<java.util.Map<String, Object>> rows = results.stream()
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
                    map.put("pinned", row.length > 6 && truthy(row[6]));
                    // 分叉出身（首条消息的）：「从此分叉」的产物非空，前端据此渲染「分支自 …」
                    String parentId = row.length > 7 && row[7] != null ? row[7].toString() : null;
                    map.put("parentConversationId", parentId);
                    map.put("parentTitle", parentId == null ? null : parentTitles.get(parentId));
                    return map;
                })
                // toCollection 而不是 toList()：后者不保证可变，而下面要就地排序
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        rows.sort(java.util.Comparator.comparing(
                (java.util.Map<String, Object> r) -> Boolean.TRUE.equals(r.get("pinned")) ? 0 : 1));
        return rows;
    }

    /**
     * 会话置顶标记的取值归一。
     *
     * <p>JPQL 标量子查询的返回类型在不同方言下不完全一致（H2 回 Boolean，
     * 部分 MySQL 驱动把 BIT(1)/TINYINT 回成 Number），按 Boolean 强转会在某一端
     * 静默失败——表现是「置顶点了没反应」，而没有任何地方报错。
     */
    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return value != null && "true".equalsIgnoreCase(value.toString());
    }

    /**
     * 一批会话 id → 显示标题（dev-board#779 K18）。取标题的口径与 {@link #forkConversation}
     * 一致：storedTitle 优先，没有就用用户第一问（助手那整段回答在角标里只会被截成一团）。
     * 查不到的会话根本不进返回 map——调用方据此渲染成「无标题的父会话」而不是编一个。
     */
    private java.util.Map<String, String> resolveConversationTitles(java.util.Set<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> titles = new java.util.HashMap<>();
        for (Object[] row : repository.findConversationTitleCandidates(conversationIds)) {
            if (row == null || row.length == 0 || row[0] == null) continue;
            String storedTitle = row.length > 1 && row[1] != null ? row[1].toString() : null;
            String firstUserMessage = row.length > 2 && row[2] != null ? row[2].toString() : null;
            String title = storedTitle != null && !storedTitle.isBlank()
                    ? storedTitle
                    : (firstUserMessage != null && !firstUserMessage.isBlank() ? cleanTitle(firstUserMessage) : null);
            if (title != null && !title.isBlank()) titles.put(row[0].toString(), title);
        }
        return titles;
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
        return forkConversation(conversationId, userId, LangText.of("（分支）", " (branch)"), null, null);
    }

    /**
     * fork 的带出身信息版本（dev-board#779 K1，K18 复用）。
     *
     * <p>{@code parentConversationId} 与 {@code branchFromMessageId} 写在<b>首条复制行</b>上——
     * 与 conversationTitle / sourceChannel 同款，因为本仓没有 ai_conversation 表，
     * 会话级元数据一律挂首行。本批 UI 不展示这两个字段，先落库是「数据模型先于 UI」：
     * 没有它们，「这条存档是从哪儿岔出来的」将来只能靠标题里的字符串猜。
     *
     * @param titleSuffix        追加在原标题后的后缀（如「（分支）」「· 回退前存档」）
     * @param parentConversation 父会话 id；null = 不记出身
     * @param branchFromMessage  分叉点消息主键；null = 整条复制、没有特定分叉点
     */
    @org.springframework.transaction.annotation.Transactional
    public String forkConversation(String conversationId, Long userId, String titleSuffix,
                                   String parentConversation, Long branchFromMessage) {
        return forkConversation(conversationId, userId, titleSuffix, parentConversation, branchFromMessage, null);
    }

    /**
     * fork 的按消息截断版本（dev-board#779 K18「从此分叉」）。
     *
     * @param untilMessage 只复制到这条为止（含该条）；null = 整条复制。判据与回退的删除判据
     *                     互为镜像：显示顺序 (createdAt, id) 的字典序 &lt;= 目标。只按 createdAt
     *                     会让与分叉点<b>同刻</b>落库的那条助手回复也跟过来——那正是用户想岔开的
     *                     那一答；只按 id 则假设 id 与时间同序，fork / 镜像导入的行不保证。
     */
    @org.springframework.transaction.annotation.Transactional
    public String forkConversation(String conversationId, Long userId, String titleSuffix,
                                   String parentConversation, Long branchFromMessage, Long untilMessage) {
        List<ProjectAiMessage> all = repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("会话不存在或为空", "Conversation not found or empty"));
        }
        final List<ProjectAiMessage> source = untilMessage == null ? all : cutAt(all, untilMessage);
        // 随机尾巴防撞号：原来是纯 conv-<毫秒>，同一毫秒里 fork 两次会落进同一条会话，
        // 后一份存档把前一份吞掉——而存档恰恰是为了不丢数据（回退连点两下就能触发）。
        // 形状仍是 conv-…，与签发端点的 conv-<毫秒>-<随机> 同构，没有任何解析方依赖格式。
        String newConversationId = "conv-" + System.currentTimeMillis() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        String baseTitle = null;
        for (ProjectAiMessage m : source) {
            if (baseTitle == null && m.getConversationTitle() != null && !m.getConversationTitle().isBlank()) {
                baseTitle = m.getConversationTitle();
            }
        }
        if (baseTitle == null || baseTitle.isBlank()) {
            // 还没来得及起标题时（LLM 起标题是异步的，回退往往发生在它落库之前）用
            // 用户第一条消息 —— 与 listConversations 的预览回退同口径。原来取的是
            // 最后一条消息，那通常是助手的整段回答：剥完标签仍是几十上百字，
            // 在「近期对话」里被截断后连后缀都看不见，用户根本认不出哪条是存档。
            baseTitle = source.stream()
                    .filter(m -> "USER".equalsIgnoreCase(m.getRole()))
                    .map(ProjectAiMessage::getContent)
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .map(this::cleanTitle)
                    .orElseGet(() -> cleanTitle(source.get(source.size() - 1).getContent()));
        }
        String suffix = titleSuffix == null ? "" : titleSuffix;
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
            copy.setClientRequestId(m.getClientRequestId());
            copy.setConversationId(newConversationId);
            copy.setCreatedAt(m.getCreatedAt());
            if (first) {
                copy.setConversationTitle(forkTitle);
                copy.setParentConversationId(parentConversation);
                copy.setBranchFromMessageId(branchFromMessage);
                first = false;
            }
            repository.save(copy);
        }
        return newConversationId;
    }

    /**
     * 「到这条为止（含该条）」（dev-board#779 K18）：判据是显示顺序 (createdAt, id) 的字典序，
     * 与回退的删除判据互为镜像。只按 createdAt 会让与分叉点<b>同刻</b>落库的那条助手回复
     * 也跟过来——那正是用户想岔开的那一答；只按 id 则假设 id 与时间同序，
     * fork / 镜像导入的行不保证。
     */
    private List<ProjectAiMessage> cutAt(List<ProjectAiMessage> ordered, Long untilMessage) {
        ProjectAiMessage cutoff = ordered.stream()
                .filter(m -> untilMessage.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(LangText.of(
                        "无法定位这条消息，可能它已被删除或还没保存完成，请刷新后重试",
                        "Could not locate that message: it may already be gone, or not finished saving. Refresh and try again.")));
        return ordered.stream()
                .filter(m -> m.getCreatedAt().isBefore(cutoff.getCreatedAt())
                        || (m.getCreatedAt().equals(cutoff.getCreatedAt())
                            && m.getId() != null && m.getId() <= cutoff.getId()))
                .toList();
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
     * 置顶 / 取消置顶（dev-board#796）。与标题同一存储位——写会话首条消息。
     *
     * <p>会话还没有任何消息时什么都不做（与 {@link #updateConversationTitle} 同口径）：
     * 空会话在列表里本来就不存在，没有可置顶的行。
     */
    @org.springframework.transaction.annotation.Transactional
    public void updateConversationPinned(String conversationId, boolean pinned) {
        repository.findFirstByConversationId(conversationId).ifPresent(first -> {
            first.setConversationPinned(pinned);
            repository.save(first);
        });
    }

    /** 会话是否置顶（列表之外的单查，给 metadata 端点用）。 */
    public boolean isConversationPinned(String conversationId) {
        return repository.findFirstByConversationId(conversationId)
                .map(m -> Boolean.TRUE.equals(m.getConversationPinned()))
                .orElse(false);
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

    /**
     * 把「回退到这条消息」的两种定位键解析成主键（dev-board#779 K1）。
     *
     * <p>为什么要两种：会话内<b>刚发出</b>的那条消息，前端手上只有自造的气泡 id
     * （{@code msg-<毫秒>-<序号>}）——真正的主键要等 turnExecutor 线程落库才存在，
     * POST /api/agent/chat 的回执与 input_applied 都赶在它前面，带不上。所以 live 气泡
     * 用 clientRequestId（发之前就有）；从 GET /api/ai/history 回灌的气泡有主键，用主键。
     * 两个都给时以主键为准（精确），主键定位不到再退到 clientRequestId。
     *
     * @throws IllegalArgumentException 定位不到时抛可读文案（控制器据此回 400，不是 500）
     */
    public Long resolveRollbackTarget(String conversationId, Long messageId, String clientRequestId) {
        if (messageId != null) {
            ProjectAiMessage byId = repository.findById(messageId).orElse(null);
            // 跨会话的 id 一律当「定位不到」处理：不回显它属于谁，免得成了探测别人会话的接口
            if (byId != null && conversationId != null && conversationId.equals(byId.getConversationId())) {
                return byId.getId();
            }
        }
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            ProjectAiMessage byKey = repository
                    .findFirstByConversationIdAndClientRequestIdOrderByCreatedAtAscIdAsc(
                            conversationId, clientRequestId.trim())
                    .orElse(null);
            if (byKey != null) return byKey.getId();
        }
        throw new IllegalArgumentException(LangText.of(
                "无法定位这条消息，可能它已被删除或还没保存完成，请刷新后重试",
                "Could not locate that message: it may already be gone, or not finished saving. Refresh and try again."));
    }

    /**
     * 回退（edit-and-resend）：删掉目标消息<b>及其之后</b>的全部消息。
     *
     * <p>语义与前端一致——前端把目标正文回填输入框让用户改了重发，目标要是留在库里，
     * 历史里就会出现「原始提问 + 改过的提问」两条连着的 USER 行：刷新页面那条本以为撤销掉的
     * 提问会复活，而 ContextAssemblerService 的历史栈直接读库，模型会把旧要求也一起执行。
     *
     * <p><b>破坏性，调用方通常应该用 {@link #rollbackWithArchive}</b>——那条会先把原路径整条
     * 存档再截断。本方法留给「确实只想删」的内部调用与测试。
     */
    @org.springframework.transaction.annotation.Transactional
    public void truncateHistory(String conversationId, Long messageId) {
        ProjectAiMessage message = repository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));

        if (!message.getConversationId().equals(conversationId)) {
            throw new IllegalArgumentException("Message does not belong to conversation: " + conversationId);
        }

        repository.deleteFromMessageOnwards(conversationId, message.getCreatedAt(), message.getId());
    }

    /**
     * 回退前先存档（dev-board#779 K1）：整条会话 fork 成一条「…· 回退前存档」的新会话，
     * 然后才截断。<b>永不静默销毁用户数据</b>——律师常要对同一份合同试两种方案再比较，
     * 原来那条探索路径一旦删掉就找不回来了。
     *
     * <p>两步刻意放在<b>同一个事务、同一个服务方法</b>里，而不是让前端先调 fork 再调回退：
     * 那样存档成功而截断失败只是多一份存档（无害），但存档失败时前端若照样截断，
     * 数据就真没了；而且服务端做，任何客户端（Office 插件等）走这个端点都有同样的保护。
     *
     * @return 存档会话 id（前端据此告诉用户「原对话已存为分支」）
     */
    @org.springframework.transaction.annotation.Transactional
    public String rollbackWithArchive(String conversationId, Long messageId, String clientRequestId, Long userId) {
        Long targetId = resolveRollbackTarget(conversationId, messageId, clientRequestId);
        String archived = forkConversation(conversationId, userId,
                LangText.of(" · 回退前存档", " · before rollback"), conversationId, targetId);
        truncateHistory(conversationId, targetId);
        return archived;
    }

    /**
     * 从此分叉（dev-board#779 K18，审查 D-06 / F4）：把「到这条为止」复制成一条新会话，
     * <b>原会话一个字都不动</b>。
     *
     * <p>这是回退的非破坏形态。律师常要对同一份合同试两种改法再比较——改造前想换个思路
     * 只有回退一条路，而回退会把这条之后的全部对话从库里删掉（K1 之后至少有自动存档，
     * 但仍是「先破坏再补救」）。分叉不需要任何补救。
     *
     * <p>定位键与回退共用 {@link #resolveRollbackTarget}：历史回灌的气泡有主键，
     * 本次会话内刚发出的只有 clientRequestId。定位不到时抛可读文案，此时原会话与
     * 新会话都不会被创建——失败是干净的。
     *
     * @return 新会话 id
     */
    @org.springframework.transaction.annotation.Transactional
    public String forkFromMessage(String conversationId, Long userId, Long messageId, String clientRequestId) {
        Long targetId = resolveRollbackTarget(conversationId, messageId, clientRequestId);
        return forkConversation(conversationId, userId,
                LangText.of(" · 分支", " · branch"), conversationId, targetId, targetId);
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


