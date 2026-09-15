// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import com.checkba.model.entity.MemoryDocument;
import com.checkba.model.entity.MemoryDocumentSpace;
import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.Project;
import com.checkba.repository.MemoryDocumentRepository;
import com.checkba.repository.MemoryDocumentSpaceRepository;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.ProjectMemberService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryDocumentService {
    public static final String INDEX_PATH = "remember.md";
    private static final int MAX_CONTENT_CHARS = 128 * 1024;
    private static final String LINKS_START = "<!-- memory-topics:start -->";
    private static final String LINKS_END = "<!-- memory-topics:end -->";
    private static final Pattern HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]*]\\(([^)]+)\\)");

    private final MemoryDocumentRepository documents;
    private final MemoryDocumentSpaceRepository spaces;
    private final MemoryEntryRepository legacyEntries;
    private final ProjectRepository projects;
    private final ProjectMemberService projectMembers;
    private final MemoryOrganizationGateway organizations;
    private final TransactionTemplate transactions;
    private final long organizationTimeoutMillis;
    private final ExecutorService organizationExecutor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "memory-organization-context");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryDocumentService(MemoryDocumentRepository documents,
                                 MemoryDocumentSpaceRepository spaces,
                                 MemoryEntryRepository legacyEntries,
                                 ProjectRepository projects,
                                 ProjectMemberService projectMembers,
                                 MemoryOrganizationGateway organizations,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${memory.context.organization-timeout-ms:2000}") long organizationTimeoutMillis) {
        this.documents = documents;
        this.spaces = spaces;
        this.legacyEntries = legacyEntries;
        this.projects = projects;
        this.projectMembers = projectMembers;
        this.organizations = organizations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.organizationTimeoutMillis = Math.max(50, organizationTimeoutMillis);
    }

    public List<MemorySpaceView> listSpaces(Long userId, Long projectId) {
        requireUser(userId);
        List<MemorySpaceView> result = required(transactions.execute(status -> listLocalSpaces(userId, projectId)));
        List<MemorySpaceView> org = organizations.listSpaces(userId);
        result.add(findOrg(org, "team", "团队记忆", "团队记忆暂不可用"));
        result.add(findOrg(org, "firm", "律所记忆", "律所记忆暂不可用"));
        return result;
    }

    private List<MemorySpaceView> listLocalSpaces(Long userId, Long projectId) {
        List<MemorySpaceView> result = new ArrayList<>(4);
        MemoryDocumentSpace user = ensureSpace("user", String.valueOf(userId), "个人记忆");
        result.add(view(user, true, true, true, null));

        if (projectId == null) {
            result.add(MemorySpaceView.unavailable("project", "项目记忆", "请选择项目"));
        } else {
            if (!projectMembers.hasReadPermission(projectId, userId)) {
                throw denied("无权访问该项目的记忆");
            }
            Project project = projects.findById(projectId)
                    .orElseThrow(() -> bad("项目不存在"));
            MemoryDocumentSpace projectSpace = ensureSpace("project", String.valueOf(projectId),
                    project.getName() == null || project.getName().isBlank() ? "项目记忆" : project.getName());
            result.add(view(projectSpace, true,
                    projectMembers.hasWritePermission(projectId, userId), true, null));
        }

        return result;
    }

    public List<MemoryFileView> listFiles(Long userId, String spaceId) {
        if (organizations.handles(spaceId)) return organizations.listFiles(requireUser(userId), spaceId);
        return required(transactions.execute(status -> {
            LocalAccess access = requireLocalAccess(userId, spaceId, false);
            ensureIndex(access.space());
            return documents.findBySpaceIdAndDeletedFalseOrderByPathAsc(spaceId).stream()
                    .map(d -> toView(d, access.writable(), false)).toList();
        }));
    }

    public MemoryFileView read(Long userId, String spaceId, String path) {
        validatePath(path);
        if (organizations.handles(spaceId)) return organizations.read(requireUser(userId), spaceId, path);
        return required(transactions.execute(status -> {
            LocalAccess access = requireLocalAccess(userId, spaceId, false);
            if (INDEX_PATH.equals(path)) ensureIndex(access.space());
            MemoryDocument document = documents.findBySpaceIdAndPath(spaceId, path)
                    .filter(d -> !d.isDeleted()).orElseThrow(() -> bad("记忆文件不存在"));
            return toView(document, access.writable(), true);
        }));
    }

    public MemoryFileView write(Long userId, String spaceId, String path,
                                String content, long expectedRevision) {
        validatePath(path);
        validateContent(content);
        if (organizations.handles(spaceId)) {
            return organizations.write(requireUser(userId), spaceId, path, content, expectedRevision);
        }
        return required(transactions.execute(status -> {
            LocalAccess access = requireLocalAccess(userId, spaceId, true);
            return writeLocal(access.space(), userId, path, content, expectedRevision, null);
        }));
    }

    public void delete(Long userId, String spaceId, String path, long expectedRevision) {
        validatePath(path);
        if (INDEX_PATH.equals(path)) throw bad("remember.md 是空间索引，不能删除；可以编辑或清空正文");
        if (organizations.handles(spaceId)) {
            organizations.delete(requireUser(userId), spaceId, path, expectedRevision);
            return;
        }
        transactions.executeWithoutResult(status -> {
            LocalAccess access = requireLocalAccess(userId, spaceId, true);
            MemoryDocument document = documents.findLockedBySpaceIdAndPath(spaceId, path)
                    .filter(d -> !d.isDeleted()).orElseThrow(() -> bad("记忆文件不存在"));
            checkRevision(document, expectedRevision);
            document.setDeleted(true);
            document.setRevision(document.getRevision() + 1);
            document.setModifiedBy(userId);
            document.setUpdatedAt(LocalDateTime.now());
            documents.save(document);
            if (document.getSourceMemoryUid() != null) {
                legacyEntries.findFirstByUid(document.getSourceMemoryUid()).ifPresent(legacyEntries::delete);
            }
            refreshIndex(access.space(), userId);
        });
    }

    public String download(Long userId, String spaceId, String path) {
        if (organizations.handles(spaceId)) return organizations.download(requireUser(userId), spaceId, path);
        return read(userId, spaceId, path).content();
    }

    public List<MemoryFileView> search(Long userId, String spaceId, String query, int limit) {
        if (query == null || query.isBlank()) throw bad("搜索词不能为空");
        int capped = Math.max(1, Math.min(limit, 20));
        if (organizations.handles(spaceId)) {
            return organizations.search(requireUser(userId), spaceId, query, capped);
        }
        return required(transactions.execute(status -> {
            LocalAccess access = requireLocalAccess(userId, spaceId, false);
            String needle = query.toLowerCase(Locale.ROOT);
            return documents.findBySpaceIdAndDeletedFalseOrderByPathAsc(spaceId).stream()
                    .filter(d -> d.getPath().toLowerCase(Locale.ROOT).contains(needle)
                            || d.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                            || d.getContent().toLowerCase(Locale.ROOT).contains(needle))
                    .limit(capped).map(d -> toView(d, access.writable(), true)).toList();
        }));
    }

    @Transactional
    public MemoryFileView migrateLegacyEntry(MemoryEntry entry) {
        if (entry == null) return null;
        String scope = entry.getScope();
        if (!MemoryEntry.MemoryScope.USER.equals(scope)
                && !MemoryEntry.MemoryScope.GLOBAL.equals(scope)
                && !MemoryEntry.MemoryScope.PROJECT.equals(scope)
                && !MemoryEntry.MemoryScope.FILE.equals(scope)
                && !MemoryEntry.MemoryScope.CONVERSATION.equals(scope)) return null;
        boolean userScope = MemoryEntry.MemoryScope.USER.equals(scope)
                || MemoryEntry.MemoryScope.GLOBAL.equals(scope);
        Long owner = userScope ? entry.getUserId() : entry.getProjectId();
        if (owner == null) return null;
        if (entry.getUid() == null || entry.getUid().isBlank()) {
            entry.setUid(UUID.randomUUID().toString());
            legacyEntries.save(entry);
        }
        Optional<MemoryDocument> existing = documents.findBySourceMemoryUid(entry.getUid());
        if (existing.isPresent() && existing.get().isDeleted()) return null;
        MemoryDocumentSpace space = ensureSpace(userScope ? "user" : "project", String.valueOf(owner),
                userScope ? "个人记忆" : projects.findById(owner).map(Project::getName).orElse("项目记忆"));
        String path = documentPath(entry);
        String content = documentContent(entry);
        validatePath(path);
        validateContent(content);
        if (existing.isPresent() && !existing.get().isDeleted()) {
            MemoryDocument current = existing.get();
            if (!current.getPath().equals(path) || !current.getContent().equals(content)) {
                current.setPath(path);
                current.setTitle(title(path, content));
                current.setContent(content);
                current.setRevision(Math.max(current.getRevision() + 1, documentRevision(entry)));
                current.setUpdatedAt(LocalDateTime.now());
                documents.save(current);
            }
            return toView(current, true, true);
        }
        return writeLocal(space, entry.getUserId(), path, content, 0, entry.getUid());
    }

    /** Git 墓碑回灌的统一落点：旧行、文档墓碑和索引更新在同一事务中完成。 */
    @Transactional
    public void tombstoneSourceAndDeleteLegacy(String sourceMemoryUid) {
        if (sourceMemoryUid == null) return;
        documents.findBySourceMemoryUid(sourceMemoryUid).filter(d -> !d.isDeleted()).ifPresent(d -> {
            d.setDeleted(true);
            d.setRevision(d.getRevision() + 1);
            d.setUpdatedAt(LocalDateTime.now());
            documents.save(d);
            spaces.findById(d.getSpaceId()).ifPresent(s -> refreshIndex(s, null));
        });
        legacyEntries.findFirstByUid(sourceMemoryUid).ifPresent(legacyEntries::delete);
    }

    /** 每轮注入的索引快照；组织列表只请求一次，详细主题文件仍由工具按需读取。 */
    public String contextIndexes(Long userId, Long projectId, int maxChars) {
        int budget = Math.max(0, maxChars);
        if (userId == null || budget == 0) return "";
        StringBuilder out = new StringBuilder();
        List<MemorySpaceView> localSpaces = required(
                transactions.execute(status -> listLocalSpaces(userId, projectId)));
        for (MemorySpaceView space : localSpaces) {
            if (!space.available() || !space.readable() || space.id() == null) continue;
            try {
                MemoryFileView index = read(userId, space.id(), INDEX_PATH);
                appendBounded(out, "\n## " + space.label() + " [" + space.scope() + "]\n"
                        + index.content() + "\n", budget);
            } catch (MemoryDocumentException ignored) {
                // 一个共享空间暂时不可达时，用户/项目索引仍应继续注入。
            }
            if (out.length() >= budget) break;
        }
        if (out.length() < budget) {
            for (OrganizationIndex index : organizationIndexes(userId)) {
                appendBounded(out, "\n## " + index.space().label() + " [" + index.space().scope() + "]\n"
                        + index.file().content() + "\n", budget);
                if (out.length() >= budget) break;
            }
        }
        return out.toString();
    }

    private MemoryFileView writeLocal(MemoryDocumentSpace space, Long userId, String path,
                                      String content, long expectedRevision, String sourceMemoryUid) {
        MemoryDocument document = documents.findLockedBySpaceIdAndPath(space.getId(), path).orElse(null);
        if (document == null) {
            if (expectedRevision != 0) throw conflict("记忆文件已发生变化，请刷新后重试");
            document = new MemoryDocument();
            document.setSpaceId(space.getId());
            document.setPath(path);
            document.setRevision(1);
        } else if (document.isDeleted()) {
            if (expectedRevision != 0) throw conflict("记忆文件已删除，请刷新后重试");
            document.setRevision(document.getRevision() + 1);
        } else {
            checkRevision(document, expectedRevision);
            document.setRevision(document.getRevision() + 1);
        }
        String storedContent = INDEX_PATH.equals(path) ? canonicalIndexContent(space, content) : content;
        document.setDeleted(false);
        document.setTitle(title(path, storedContent));
        document.setContent(storedContent);
        document.setModifiedBy(userId);
        document.setUpdatedAt(LocalDateTime.now());
        if (sourceMemoryUid != null) document.setSourceMemoryUid(sourceMemoryUid);
        try {
            document = documents.saveAndFlush(document);
        } catch (DataIntegrityViolationException e) {
            throw conflict("记忆文件已被另一处创建，请刷新后重试");
        }
        if (document.getSourceMemoryUid() == null) document.setSourceMemoryUid(UUID.randomUUID().toString());
        documents.saveAndFlush(document);
        syncLegacyIndex(document, space);
        if (!INDEX_PATH.equals(path)) refreshIndex(space, userId);
        return toView(document, true, true);
    }

    private void syncLegacyIndex(MemoryDocument document, MemoryDocumentSpace space) {
        MemoryEntry entry = legacyEntries.findFirstByUid(document.getSourceMemoryUid()).orElseGet(MemoryEntry::new);
        boolean derived = entry.getId() == null;
        entry.setUid(document.getSourceMemoryUid());
        if (derived) entry.setScope(space.getScope());
        entry.setUserId("user".equals(space.getScope()) ? parseLong(space.getOwnerKey()) : document.getModifiedBy());
        entry.setProjectId("project".equals(space.getScope()) ? parseLong(space.getOwnerKey()) : null);
        if (derived) {
            entry.setMemoryType("user".equals(space.getScope())
                    ? MemoryEntry.MemoryType.PREFERENCE : MemoryEntry.MemoryType.FACT);
            entry.setImportanceScore(0.7);
            entry.setIsProtected(false);
        }
        entry.setMemoryKey(document.getTitle());
        entry.setMemoryValue(document.getContent());
        Map<String, Object> metadata = entry.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.getMetadata());
        metadata.put("documentPath", document.getPath());
        metadata.put("documentRevision", document.getRevision());
        entry.setMetadata(metadata);
        legacyEntries.save(entry);
    }

    private MemoryDocument ensureIndex(MemoryDocumentSpace space) {
        MemoryDocument current = documents.findLockedBySpaceIdAndPath(space.getId(), INDEX_PATH).orElse(null);
        if (current != null && !current.isDeleted()) return current;
        MemoryDocument index = current == null ? new MemoryDocument() : current;
        index.setSpaceId(space.getId());
        index.setPath(INDEX_PATH);
        index.setTitle(space.getLabel());
        index.setContent("# " + space.getLabel() + "\n\n" + LINKS_START + "\n" + LINKS_END + "\n");
        index.setDeleted(false);
        index.setRevision(current == null ? 1 : current.getRevision() + 1);
        index.setUpdatedAt(LocalDateTime.now());
        MemoryDocument saved = documents.saveAndFlush(index);
        if (saved.getSourceMemoryUid() == null) {
            saved.setSourceMemoryUid(UUID.randomUUID().toString());
            saved = documents.saveAndFlush(saved);
        }
        syncLegacyIndex(saved, space);
        return saved;
    }

    private void refreshIndex(MemoryDocumentSpace space, Long userId) {
        MemoryDocument index = ensureIndex(space);
        List<MemoryDocument> topics = documents.findBySpaceIdAndDeletedFalseOrderByPathAsc(space.getId()).stream()
                .filter(d -> !INDEX_PATH.equals(d.getPath())).toList();
        String content = replaceManagedLinks(index.getContent(), managedLinks(topics));
        if (!content.equals(index.getContent())) {
            index.setContent(content);
            index.setRevision(index.getRevision() + 1);
            index.setModifiedBy(userId);
            index.setUpdatedAt(LocalDateTime.now());
            documents.save(index);
            syncLegacyIndex(index, space);
        }
    }

    private LocalAccess requireLocalAccess(Long userId, String spaceId, boolean write) {
        requireUser(userId);
        MemoryDocumentSpace space = spaces.findById(spaceId == null ? "" : spaceId)
                .orElseThrow(() -> denied("无权访问这个记忆空间"));
        boolean readable;
        boolean writable;
        if ("user".equals(space.getScope())) {
            readable = String.valueOf(userId).equals(space.getOwnerKey());
            writable = readable;
        } else if ("project".equals(space.getScope())) {
            Long projectId = parseLong(space.getOwnerKey());
            readable = projectId != null && projectMembers.hasReadPermission(projectId, userId);
            writable = projectId != null && projectMembers.hasWritePermission(projectId, userId);
        } else {
            throw denied("无权访问这个记忆空间");
        }
        if (!readable || (write && !writable)) throw denied(write ? "无权修改这个记忆空间" : "无权读取这个记忆空间");
        return new LocalAccess(space, writable);
    }

    private MemoryDocumentSpace ensureSpace(String scope, String ownerKey, String label) {
        return spaces.findByScopeAndOwnerKey(scope, ownerKey).orElseGet(() -> {
            MemoryDocumentSpace space = new MemoryDocumentSpace();
            space.setId(UUID.randomUUID().toString());
            space.setScope(scope);
            space.setOwnerKey(ownerKey);
            space.setLabel(label);
            try {
                return spaces.saveAndFlush(space);
            } catch (DataIntegrityViolationException e) {
                return spaces.findByScopeAndOwnerKey(scope, ownerKey).orElseThrow(() -> e);
            }
        });
    }

    private static MemorySpaceView view(MemoryDocumentSpace space, boolean readable,
                                        boolean writable, boolean available, String reason) {
        return new MemorySpaceView(space.getId(), space.getScope(), space.getLabel(),
                readable, writable, available, reason);
    }

    private static MemorySpaceView findOrg(List<MemorySpaceView> spaces, String scope,
                                           String label, String reason) {
        if (spaces != null) {
            for (MemorySpaceView s : spaces) if (scope.equals(s.scope())) return s;
        }
        return MemorySpaceView.unavailable(scope, label, reason);
    }

    private static MemoryFileView toView(MemoryDocument d, boolean writable, boolean includeContent) {
        return new MemoryFileView(d.getPath(), d.getTitle(), includeContent ? d.getContent() : null,
                d.getRevision(), d.getUpdatedAt(), writable);
    }

    private static String title(String path, String content) {
        Matcher matcher = HEADING.matcher(content == null ? "" : content);
        if (matcher.find() && !matcher.group(1).isBlank()) return matcher.group(1).trim();
        String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 3);
        return name.isBlank() ? "记忆" : name;
    }

    private static String replaceManagedLinks(String content, String block) {
        String safe = content == null ? "" : content;
        int start = safe.indexOf(LINKS_START);
        int end = safe.indexOf(LINKS_END);
        if (start >= 0 && end >= start) {
            return safe.substring(0, start) + block + safe.substring(end + LINKS_END.length());
        }
        if (!safe.endsWith("\n")) safe += "\n";
        return safe + "\n" + block + "\n";
    }

    private String canonicalIndexContent(MemoryDocumentSpace space, String content) {
        validateIndexLinks(space, content);
        List<MemoryDocument> topics = documents.findBySpaceIdAndDeletedFalseOrderByPathAsc(space.getId()).stream()
                .filter(d -> !INDEX_PATH.equals(d.getPath())).toList();
        return replaceManagedLinks(content, managedLinks(topics));
    }

    private void validateIndexLinks(MemoryDocumentSpace space, String content) {
        Matcher matcher = MARKDOWN_LINK.matcher(content == null ? "" : content);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            int anchor = target.indexOf('#');
            if (anchor >= 0) target = target.substring(0, anchor);
            if (target.isBlank() || target.contains(":") || target.startsWith("/")) continue;
            if (!target.toLowerCase(Locale.ROOT).endsWith(".md")) continue;
            validatePath(target);
            if (documents.findBySpaceIdAndPath(space.getId(), target).filter(d -> !d.isDeleted()).isEmpty()) {
                throw bad("记忆索引链接的文件不存在: " + target);
            }
        }
    }

    private static String managedLinks(List<MemoryDocument> topics) {
        StringBuilder links = new StringBuilder(LINKS_START).append('\n');
        for (MemoryDocument topic : topics) {
            links.append("- [").append(linkLabel(topic.getTitle())).append("](")
                    .append(topic.getPath()).append(")\n");
        }
        return links.append(LINKS_END).toString();
    }

    private List<OrganizationIndex> organizationIndexes(Long userId) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(organizationTimeoutMillis);
        Future<List<MemorySpaceView>> spacesFuture = organizationExecutor.submit(() -> organizations.listSpaces(userId));
        List<Future<OrganizationIndex>> reads = new ArrayList<>();
        List<OrganizationIndex> result = new ArrayList<>();
        try {
            List<MemorySpaceView> orgSpaces = spacesFuture.get(remaining(deadline), TimeUnit.NANOSECONDS);
            CompletionService<OrganizationIndex> completion = new ExecutorCompletionService<>(organizationExecutor);
            for (MemorySpaceView space : orgSpaces) {
                if (space.available() && space.readable() && space.id() != null) {
                    reads.add(completion.submit(() ->
                            new OrganizationIndex(space, organizations.read(userId, space.id(), INDEX_PATH))));
                }
            }
            for (int completed = 0; completed < reads.size(); completed++) {
                Future<OrganizationIndex> next = completion.poll(remaining(deadline), TimeUnit.NANOSECONDS);
                if (next == null) break;
                try {
                    result.add(next.get());
                } catch (ExecutionException ignored) {
                    // 一个共享空间暂时不可达时，仍保留其他索引。
                }
            }
            result.sort(Comparator.comparingInt(index -> "team".equals(index.space().scope()) ? 0 : 1));
            return result;
        } catch (TimeoutException | ExecutionException ignored) {
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            spacesFuture.cancel(true);
            reads.forEach(read -> read.cancel(true));
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new TimeoutException();
        return left;
    }

    @PreDestroy
    void closeOrganizationExecutor() {
        organizationExecutor.shutdownNow();
    }

    private static String linkLabel(String title) {
        return title == null ? "记忆" : title.replace("[", "（").replace("]", "）");
    }

    private static void validatePath(String path) {
        if (path == null || path.isBlank() || path.length() > 512 || !path.endsWith(".md")
                || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
                || path.contains(":") || path.contains("//")) throw bad("记忆文件路径不正确");
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) throw bad("记忆文件路径不正确");
        }
    }

    private static void validateContent(String content) {
        if (content == null) throw bad("记忆内容不能为空");
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_CHARS) {
            throw bad("记忆文件不能超过 128 KiB");
        }
    }

    private static void checkRevision(MemoryDocument document, long expectedRevision) {
        if (expectedRevision != document.getRevision()) throw conflict("记忆文件已发生变化，请刷新后重试");
    }

    private static String legacyMarkdown(MemoryEntry e) {
        return "---\nsource: legacy-memory-entry\nuid: " + e.getUid()
                + "\ntype: " + nullToEmpty(e.getMemoryType())
                + "\nprotected: " + Boolean.TRUE.equals(e.getIsProtected()) + "\n---\n\n# "
                + (e.getMemoryKey() == null || e.getMemoryKey().isBlank() ? "历史记忆" : e.getMemoryKey())
                + "\n\n" + nullToEmpty(e.getMemoryValue());
    }

    private static String documentPath(MemoryEntry entry) {
        Object path = entry.getMetadata() == null ? null : entry.getMetadata().get("documentPath");
        return path == null || String.valueOf(path).isBlank()
                ? "legacy/" + entry.getUid() + ".md" : String.valueOf(path);
    }

    private static String documentContent(MemoryEntry entry) {
        Object path = entry.getMetadata() == null ? null : entry.getMetadata().get("documentPath");
        return path == null ? legacyMarkdown(entry) : nullToEmpty(entry.getMemoryValue());
    }

    private static long documentRevision(MemoryEntry entry) {
        Object value = entry.getMetadata() == null ? null : entry.getMetadata().get("documentRevision");
        return value instanceof Number n ? Math.max(1, n.longValue()) : 1;
    }

    private static void appendBounded(StringBuilder out, String text, int budget) {
        if (out.length() >= budget) return;
        int left = budget - out.length();
        out.append(text, 0, Math.min(left, text.length()));
    }

    private static Long requireUser(Long userId) {
        if (userId == null) throw new MemoryDocumentException(401, "请先登录");
        return userId;
    }

    private static Long parseLong(String value) {
        try { return Long.valueOf(value); } catch (Exception e) { return null; }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static <T> T required(T value) { return Objects.requireNonNull(value); }
    private static MemoryDocumentException bad(String message) { return new MemoryDocumentException(400, message); }
    private static MemoryDocumentException denied(String message) { return new MemoryDocumentException(403, message); }
    private static MemoryDocumentException conflict(String message) { return new MemoryDocumentException(409, message); }
    private record LocalAccess(MemoryDocumentSpace space, boolean writable) {}
    private record OrganizationIndex(MemorySpaceView space, MemoryFileView file) {}
}
