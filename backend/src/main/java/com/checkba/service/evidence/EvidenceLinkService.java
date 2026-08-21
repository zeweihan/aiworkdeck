package com.checkba.service.evidence;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.FileTag;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceLinkViews.AnchorReport;
import com.checkba.service.evidence.EvidenceLinkViews.FileBrief;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.checkba.model.entity.EvidenceLink.KIND_AI;
import static com.checkba.model.entity.EvidenceLink.KIND_HUMAN;
import static com.checkba.model.entity.EvidenceLink.KIND_PLUGIN;
import static com.checkba.model.entity.EvidenceLink.STATUS_ACTIVE;
import static com.checkba.model.entity.EvidenceLink.STATUS_ORPHAN;
import static com.checkba.model.entity.EvidenceLink.STATUS_STALE;
import static com.checkba.model.entity.EvidenceLink.STATUS_UNVERIFIED;

/**
 * 证据链接的单一出口（dev-board#102）。Controller / SDK 宿主 / P1 的 AI 工具都只经这里读写两张表。
 *
 * 只注入 Repository 与 ProjectMemberService——不注入 ProjectFileService，
 * 因为 ProjectFileService 在彻底删除时要回调 {@link #onFilePurged}，注入会成环。
 *
 * 权限：读 = hasReadPermission，写 = hasWritePermission（不再是创建者私有）。
 * IDOR：docFileId 与每个 target.fileId 都必须属于 projectId。
 */
@Service
@RequiredArgsConstructor
public class EvidenceLinkService {

    private static final Set<String> KINDS = Set.of(KIND_HUMAN, KIND_AI, KIND_PLUGIN);
    private static final String LINK_KEY_PATTERN = "[A-Za-z0-9_]{1,64}";
    private static final int ANCHOR_TEXT_MAX = 1000;

    private final EvidenceLinkRepository links;
    private final EvidenceLinkTargetRepository targets;
    private final ProjectFileRepository files;
    private final FileTagRepository fileTags;
    private final ProjectMemberService members;
    private final ObjectMapper om;

    // ---------------------------------------------------------------- write

    @Transactional
    public LinkView create(Long userId, Long projectId, Long docFileId, String linkKey, String anchorText,
                           String sectionPath, String sectionTitle, String createdByKind, List<TargetInput> targetInputs) {
        requireWrite(projectId, userId);
        requireProjectFile(projectId, docFileId);
        if (targetInputs == null || targetInputs.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("至少关联一个底稿", "At least one target required"));
        }
        String kind = StringUtils.hasText(createdByKind) ? createdByKind.trim() : KIND_HUMAN;
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException(LangText.of("createdByKind 非法: ", "Illegal createdByKind: ") + kind);
        }
        String key = StringUtils.hasText(linkKey) ? linkKey.trim() : "EVID_" + Ulid.next();
        if (!key.matches(LINK_KEY_PATTERN)) {
            throw new IllegalArgumentException(LangText.of("linkKey 只能含字母数字下划线", "linkKey must be [A-Za-z0-9_]"));
        }
        if (links.findByProjectIdAndLinkKey(projectId, key).isPresent()) {
            throw new IllegalArgumentException(LangText.of("链接已存在: ", "Link exists: ") + key);
        }
        // 先把全部 target 校验完再落库，避免半截写入
        for (TargetInput t : targetInputs) validateTarget(projectId, t, true);

        LocalDateTime now = LocalDateTime.now();
        EvidenceLink l = new EvidenceLink();
        l.setProjectId(projectId);
        l.setDocFileId(docFileId);
        l.setLinkKey(key);
        l.setAnchorText(truncate(anchorText));
        l.setAnchorHash(AnchorHash.of(anchorText));
        l.setSectionPath(sectionPath);
        l.setSectionTitle(sectionTitle);
        l.setStatus(KIND_AI.equals(kind) ? STATUS_UNVERIFIED : STATUS_ACTIVE);
        l.setCreatedByKind(kind);
        l.setCreatedBy(userId);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        l = links.save(l);

        int order = 0;
        Set<String> seen = new HashSet<>();
        for (TargetInput t : targetInputs) {
            String h = locatorHash(t.locatorJson());
            if (!seen.add(t.fileId() + ":" + h)) continue;
            saveTarget(l, t, h, order++, kind, userId, now);
        }
        return view(l);
    }

    @Transactional
    public LinkView addTargets(Long userId, Long projectId, String linkKey, List<TargetInput> targetInputs) {
        requireWrite(projectId, userId);
        EvidenceLink l = requireLink(projectId, linkKey);
        if (targetInputs == null || targetInputs.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("至少关联一个底稿", "At least one target required"));
        }
        for (TargetInput t : targetInputs) validateTarget(projectId, t, true);

        List<EvidenceLinkTarget> existing = targets.findByLinkIdOrderBySortOrderAscIdAsc(l.getId());
        int order = 0;
        for (EvidenceLinkTarget t : existing) {
            if (t.getSortOrder() != null && t.getSortOrder() >= order) order = t.getSortOrder() + 1;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean added = false;
        for (TargetInput t : targetInputs) {
            String h = locatorHash(t.locatorJson());
            if (targets.existsByLinkIdAndFileIdAndLocatorHash(l.getId(), t.fileId(), h)) continue;
            saveTarget(l, t, h, order++, KIND_HUMAN, userId, now);
            added = true;
        }
        if (added) {
            l.setUpdatedAt(now);
            links.save(l);
        }
        return view(l);
    }

    /** null 字段 = 不改。 */
    @Transactional
    public TargetView updateTarget(Long userId, Long projectId, Long targetId, TargetInput patch) {
        requireWrite(projectId, userId);
        EvidenceLinkTarget t = requireTarget(projectId, targetId);
        if (patch == null) return targetView(t, fileBriefs(List.of(t.getFileId())));
        if (patch.relation() != null) {
            if (!EvidenceLinkTarget.RELATIONS.contains(patch.relation())) {
                throw new IllegalArgumentException(LangText.of("relation 非法: ", "Illegal relation: ") + patch.relation());
            }
            t.setRelation(patch.relation());
        }
        if (patch.method() != null) {
            if (!patch.method().isEmpty() && !EvidenceLinkTarget.METHODS.contains(patch.method())) {
                throw new IllegalArgumentException(LangText.of("method 非法: ", "Illegal method: ") + patch.method());
            }
            t.setMethod(patch.method().isEmpty() ? null : patch.method());
        }
        if (patch.confidence() != null) {
            validateConfidence(patch.confidence());
            t.setConfidence(patch.confidence());
        }
        if (patch.note() != null) t.setNote(patch.note());
        if (patch.locatorJson() != null) {
            String h = locatorHash(patch.locatorJson());
            if (!h.equals(t.getLocatorHash())
                    && targets.existsByLinkIdAndFileIdAndLocatorHash(t.getLinkId(), t.getFileId(), h)) {
                throw new IllegalArgumentException(LangText.of("同一位置已关联", "Same location already linked"));
            }
            t.setLocatorJson(patch.locatorJson().isBlank() ? null : patch.locatorJson());
            t.setLocatorHash(h);
        }
        t = targets.save(t);
        touch(t.getLinkId());
        return targetView(t, fileBriefs(List.of(t.getFileId())));
    }

    @Transactional
    public void removeTarget(Long userId, Long projectId, Long targetId) {
        requireWrite(projectId, userId);
        EvidenceLinkTarget t = requireTarget(projectId, targetId);
        targets.delete(t);
        touch(t.getLinkId());
    }

    @Transactional
    public void delete(Long userId, Long projectId, String linkKey) {
        requireWrite(projectId, userId);
        EvidenceLink l = requireLink(projectId, linkKey);
        targets.deleteByLinkId(l.getId());
        links.delete(l);
    }

    // ---------------------------------------------------------------- state machine

    /**
     * worker check_link_anchors 的结果回写。返回状态发生变化的 linkKey（按入参顺序）。
     * exists=false → orphan（任何状态）；文字 hash 变了且状态在 active/unverified/stale → stale；
     * orphan 不会因 exists=true 复活（复活只走 rebind）。每条都刷 checkedAt。
     */
    @Transactional
    public List<String> reportAnchors(Long userId, Long projectId, Long docFileId, List<AnchorReport> reports) {
        requireWrite(projectId, userId);
        List<String> changed = new ArrayList<>();
        if (reports == null || reports.isEmpty()) return changed;
        Map<String, EvidenceLink> byKey = new HashMap<>();
        for (EvidenceLink l : links.findByProjectIdAndDocFileIdOrderByIdAsc(projectId, docFileId)) {
            byKey.put(l.getLinkKey(), l);
        }
        LocalDateTime now = LocalDateTime.now();
        for (AnchorReport r : reports) {
            if (r == null || r.linkKey() == null) continue;
            EvidenceLink l = byKey.get(r.linkKey());
            if (l == null) continue;
            String before = l.getStatus();
            if (!r.exists()) {
                l.setStatus(STATUS_ORPHAN);
            } else if (!STATUS_ORPHAN.equals(before)) {
                String h = AnchorHash.of(r.text());
                if (!Objects.equals(h, l.getAnchorHash())) l.setStatus(STATUS_STALE);
            }
            l.setCheckedAt(now);
            if (!Objects.equals(before, l.getStatus())) {
                l.setUpdatedAt(now);
                changed.add(r.linkKey());
            }
            links.save(l);
        }
        return changed;
    }

    /** 用户「保留关联」：stale/unverified → active，anchorText/anchorHash 刷成当前文字。orphan 不能保留。 */
    @Transactional
    public LinkView keepAnchor(Long userId, Long projectId, String linkKey, String currentText) {
        requireWrite(projectId, userId);
        EvidenceLink l = requireLink(projectId, linkKey);
        if (STATUS_ORPHAN.equals(l.getStatus())) {
            throw new IllegalArgumentException(LangText.of("书签已不在文档里，请重新指定", "Bookmark is gone; rebind instead"));
        }
        LocalDateTime now = LocalDateTime.now();
        if (currentText != null) {
            l.setAnchorText(truncate(currentText));
            l.setAnchorHash(AnchorHash.of(currentText));
        }
        l.setStatus(STATUS_ACTIVE);
        l.setCheckedAt(now);
        l.setUpdatedAt(now);
        return view(links.save(l));
    }

    /** 用户「重新指定」：换书签名（newLinkKey），任何状态 → active。 */
    @Transactional
    public LinkView rebind(Long userId, Long projectId, String linkKey, String newLinkKey, String anchorText,
                           String sectionPath, String sectionTitle) {
        requireWrite(projectId, userId);
        EvidenceLink l = requireLink(projectId, linkKey);
        String key = newLinkKey == null ? "" : newLinkKey.trim();
        if (!key.matches(LINK_KEY_PATTERN)) {
            throw new IllegalArgumentException(LangText.of("linkKey 只能含字母数字下划线", "linkKey must be [A-Za-z0-9_]"));
        }
        if (!key.equals(l.getLinkKey()) && links.findByProjectIdAndLinkKey(projectId, key).isPresent()) {
            throw new IllegalArgumentException(LangText.of("链接已存在: ", "Link exists: ") + key);
        }
        LocalDateTime now = LocalDateTime.now();
        l.setLinkKey(key);
        l.setAnchorText(truncate(anchorText));
        l.setAnchorHash(AnchorHash.of(anchorText));
        if (sectionPath != null) l.setSectionPath(sectionPath);
        if (sectionTitle != null) l.setSectionTitle(sectionTitle);
        l.setStatus(STATUS_ACTIVE);
        l.setCheckedAt(now);
        l.setUpdatedAt(now);
        return view(links.save(l));
    }

    /**
     * ProjectFileService 彻底删除文件时调用：删该文件的全部 target，target 清空的 link 标 orphan。
     * 这是唯一由后端改状态的例外（worker 看不见文件系统）。无权限校验——调用方已经校过。
     */
    @Transactional
    public void onFilePurged(Long projectId, Long fileId) {
        if (fileId == null) return;
        List<EvidenceLinkTarget> hit = targets.findByFileId(fileId);
        if (hit.isEmpty()) return;
        Set<Long> linkIds = new LinkedHashSet<>();
        for (EvidenceLinkTarget t : hit) linkIds.add(t.getLinkId());
        targets.deleteAll(hit);
        LocalDateTime now = LocalDateTime.now();
        for (EvidenceLink l : links.findByProjectIdAndIdIn(projectId, linkIds)) {
            if (targets.findByLinkIdOrderBySortOrderAscIdAsc(l.getId()).isEmpty()
                    && !STATUS_ORPHAN.equals(l.getStatus())) {
                l.setStatus(STATUS_ORPHAN);
                l.setUpdatedAt(now);
                links.save(l);
            }
        }
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public LinkView getByKey(Long userId, Long projectId, String linkKey) {
        requireRead(projectId, userId);
        return view(requireLink(projectId, linkKey));
    }

    @Transactional(readOnly = true)
    public List<LinkView> listByDoc(Long userId, Long projectId, Long docFileId, String status, String sectionPathPrefix) {
        requireRead(projectId, userId);
        List<EvidenceLink> rows;
        if (StringUtils.hasText(sectionPathPrefix)) {
            rows = links.findByProjectIdAndDocFileIdAndSectionPathStartingWithOrderByIdAsc(projectId, docFileId, sectionPathPrefix);
            if (StringUtils.hasText(status)) {
                rows = rows.stream().filter(l -> status.equals(l.getStatus())).toList();
            }
        } else if (StringUtils.hasText(status)) {
            rows = links.findByProjectIdAndDocFileIdAndStatusOrderByIdAsc(projectId, docFileId, status);
        } else {
            rows = links.findByProjectIdAndDocFileIdOrderByIdAsc(projectId, docFileId);
        }
        return views(rows);
    }

    /** 反查：这份底稿被哪些锚点引用。别家项目的 link 靠 findByProjectIdAndIdIn 过滤掉。 */
    @Transactional(readOnly = true)
    public List<LinkView> listByFile(Long userId, Long projectId, Long fileId) {
        requireRead(projectId, userId);
        Set<Long> linkIds = new LinkedHashSet<>();
        for (EvidenceLinkTarget t : targets.findByFileId(fileId)) linkIds.add(t.getLinkId());
        if (linkIds.isEmpty()) return List.of();
        List<EvidenceLink> rows = new ArrayList<>(links.findByProjectIdAndIdIn(projectId, linkIds));
        rows.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return views(rows);
    }

    /** 主体视图：targets.file 挂了该 PARTY 标签的 link。 */
    @Transactional(readOnly = true)
    public List<LinkView> listByParty(Long userId, Long projectId, Long docFileId, Long tagId) {
        requireRead(projectId, userId);
        Set<Long> taggedFiles = new HashSet<>();
        for (FileTag ft : fileTags.findByTagId(tagId)) taggedFiles.add(ft.getFileId());
        if (taggedFiles.isEmpty()) return List.of();
        List<LinkView> out = new ArrayList<>();
        for (LinkView v : views(links.findByProjectIdAndDocFileIdOrderByIdAsc(projectId, docFileId))) {
            for (TargetView t : v.targets()) {
                if (taggedFiles.contains(t.fileId())) { out.add(v); break; }
            }
        }
        return out;
    }

    /** 文件树「被引用 N 次」角标。无权限校验——Controller 负责 requireRead。 */
    @Transactional(readOnly = true)
    public Map<Long, Long> refCounts(Long projectId, Collection<Long> fileIds) {
        Map<Long, Long> out = new HashMap<>();
        if (fileIds == null || fileIds.isEmpty()) return out;
        for (Object[] row : targets.countByFileIds(fileIds)) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** sha256(canonical locatorJson)：键递归排序后序列化；空/空白 = "-"；非法 JSON 抛 IllegalArgumentException。 */
    static String locatorHash(String json) {
        if (json == null || json.isBlank()) return EvidenceLinkTarget.EMPTY_LOCATOR_HASH;
        return AnchorHash.of(canonical(json));
    }

    private static final ObjectMapper CANON = new ObjectMapper();

    static String canonical(String json) {
        JsonNode node;
        try {
            node = CANON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(LangText.of("locatorJson 不是合法 JSON", "locatorJson is not valid JSON"));
        }
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException(LangText.of("locatorJson 不是合法 JSON", "locatorJson is not valid JSON"));
        }
        try {
            return CANON.writeValueAsString(sortKeys(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sortKeys(JsonNode n) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), sortKeys(e.getValue()));
            }
            ObjectNode o = CANON.createObjectNode();
            sorted.forEach(o::set);
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = CANON.createArrayNode();
            for (JsonNode c : n) a.add(sortKeys(c));
            return a;
        }
        return n;
    }

    private void requireRead(Long projectId, Long userId) {
        if (userId == null || projectId == null || !members.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project"));
        }
    }

    private void requireWrite(Long projectId, Long userId) {
        if (userId == null || projectId == null || !members.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限修改该项目", "No write access to this project"));
        }
    }

    private ProjectFile requireProjectFile(Long projectId, Long fileId) {
        ProjectFile f = fileId == null ? null : files.findById(fileId).orElse(null);
        if (f == null || !projectId.equals(f.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于该项目: ", "File not in project: ") + fileId);
        }
        return f;
    }

    private EvidenceLink requireLink(Long projectId, String linkKey) {
        if (!StringUtils.hasText(linkKey)) {
            throw new IllegalArgumentException(LangText.of("linkKey 不能为空", "linkKey must not be empty"));
        }
        return links.findByProjectIdAndLinkKey(projectId, linkKey.trim())
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("链接不存在", "Link not found")));
    }

    private EvidenceLinkTarget requireTarget(Long projectId, Long targetId) {
        EvidenceLinkTarget t = targetId == null ? null : targets.findById(targetId).orElse(null);
        if (t == null) throw new IllegalArgumentException(LangText.of("底稿位置不存在", "Target not found"));
        EvidenceLink l = links.findById(t.getLinkId()).orElse(null);
        if (l == null || !projectId.equals(l.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("底稿位置不存在", "Target not found"));
        }
        return t;
    }

    private void validateTarget(Long projectId, TargetInput t, boolean requireFile) {
        if (t == null) throw new IllegalArgumentException(LangText.of("target 不能为空", "target must not be null"));
        if (requireFile) requireProjectFile(projectId, t.fileId());
        if (t.relation() != null && !EvidenceLinkTarget.RELATIONS.contains(t.relation())) {
            throw new IllegalArgumentException(LangText.of("relation 非法: ", "Illegal relation: ") + t.relation());
        }
        if (StringUtils.hasText(t.method()) && !EvidenceLinkTarget.METHODS.contains(t.method())) {
            throw new IllegalArgumentException(LangText.of("method 非法: ", "Illegal method: ") + t.method());
        }
        if (t.confidence() != null) validateConfidence(t.confidence());
        locatorHash(t.locatorJson()); // 非法 JSON 在这里就抛
    }

    private static void validateConfidence(Short c) {
        if (c < 0 || c > 100) {
            throw new IllegalArgumentException(LangText.of("confidence 必须在 0-100", "confidence must be 0-100"));
        }
    }

    private void saveTarget(EvidenceLink l, TargetInput in, String locatorHash, int order, String kind, Long userId,
                            LocalDateTime now) {
        EvidenceLinkTarget t = new EvidenceLinkTarget();
        t.setLinkId(l.getId());
        t.setFileId(in.fileId());
        t.setLocatorJson(StringUtils.hasText(in.locatorJson()) ? in.locatorJson() : null);
        t.setLocatorHash(locatorHash);
        t.setRelation(in.relation() == null ? EvidenceLinkTarget.RELATION_SUPPORTS : in.relation());
        t.setMethod(StringUtils.hasText(in.method()) ? in.method() : null);
        t.setConfidence(in.confidence());
        t.setNote(in.note());
        t.setSortOrder(order);
        t.setCreatedByKind(kind);
        t.setCreatedBy(userId);
        t.setCreatedAt(now);
        targets.save(t);
    }

    private void touch(Long linkId) {
        links.findById(linkId).ifPresent(l -> {
            l.setUpdatedAt(LocalDateTime.now());
            links.save(l);
        });
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > ANCHOR_TEXT_MAX ? s.substring(0, ANCHOR_TEXT_MAX) : s;
    }

    private LinkView view(EvidenceLink l) {
        return views(List.of(l)).get(0);
    }

    /** targets 一次 findByLinkIdIn + files 一次 findAllById，不 N+1。 */
    private List<LinkView> views(List<EvidenceLink> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> linkIds = rows.stream().map(EvidenceLink::getId).toList();
        Map<Long, List<EvidenceLinkTarget>> byLink = new LinkedHashMap<>();
        Set<Long> fileIds = new LinkedHashSet<>();
        for (EvidenceLinkTarget t : targets.findByLinkIdInOrderBySortOrderAscIdAsc(linkIds)) {
            byLink.computeIfAbsent(t.getLinkId(), k -> new ArrayList<>()).add(t);
            fileIds.add(t.getFileId());
        }
        Map<Long, FileBrief> briefs = fileBriefs(fileIds);
        List<LinkView> out = new ArrayList<>(rows.size());
        for (EvidenceLink l : rows) {
            List<TargetView> tv = new ArrayList<>();
            for (EvidenceLinkTarget t : byLink.getOrDefault(l.getId(), List.of())) tv.add(targetView(t, briefs));
            out.add(new LinkView(l.getId(), l.getLinkKey(), l.getDocFileId(), l.getAnchorText(), l.getAnchorHash(),
                    l.getSectionPath(), l.getSectionTitle(), l.getStatus(), l.getCreatedByKind(),
                    l.getCreatedAt(), l.getUpdatedAt(), tv));
        }
        return out;
    }

    private Map<Long, FileBrief> fileBriefs(Collection<Long> fileIds) {
        Map<Long, FileBrief> out = new HashMap<>();
        if (fileIds.isEmpty()) return out;
        for (ProjectFile f : files.findAllById(fileIds)) {
            out.put(f.getId(), new FileBrief(f.getId(), f.getName(), f.getFileType(), f.getParentId(),
                    Boolean.TRUE.equals(f.getIsDeleted())));
        }
        return out;
    }

    private TargetView targetView(EvidenceLinkTarget t, Map<Long, FileBrief> briefs) {
        JsonNode locator = null;
        if (StringUtils.hasText(t.getLocatorJson())) {
            try {
                locator = om.readTree(t.getLocatorJson());
            } catch (Exception ignore) {
                locator = null;
            }
        }
        return new TargetView(t.getId(), t.getFileId(), briefs.get(t.getFileId()), locator, t.getRelation(),
                t.getMethod(), t.getConfidence(), t.getNote());
    }
}
