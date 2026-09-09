// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.completion;

import com.checkba.model.entity.CompletionEntry;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.User;
import com.checkba.repository.CompletionEntryRepository;
import com.checkba.repository.DocInsightEntityRepository;
import com.checkba.repository.ProjectVariableRepository;
import com.checkba.repository.UserVariableRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.insight.DocInsightService;
import com.checkba.service.insight.DocInsightViews.EntityView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 候选与学习只访问本地数据库；外部检索独立放在用户明确触发的 lookup 入口。 */
@Service
@RequiredArgsConstructor
public class CompletionService {
    public static final int LIMIT = 2000;
    private static final Set<String> KINDS = Set.of("COMPANY", "PERSON", "LAW", "ARTICLE", "CASE", "WORD", "PHRASE");
    private static final Pattern ARTICLE = Pattern.compile("第[零〇一二三四五六七八九十百千万两0-9]+条");
    private final CompletionEntryRepository entries;
    private final DocInsightEntityRepository insights;
    private final ProjectVariableRepository projectVariables;
    private final UserVariableRepository userVariables;
    private final ProjectMemberService members;
    private final EntityManager entityManager;
    private final DocInsightService insightService;
    private final ObjectMapper objectMapper;

    // 网络查询不占用数据库事务；成功后经代理单独开事务保存。
    @Autowired
    @Lazy
    CompletionService self;

    public record Item(String id, String text, String kind, String source, String scope,
                       Long entityId, String detail, long uses, boolean hasDetail) {}
    public record Candidates(List<Item> items, int limit) {}
    public record LearnEntry(String text, String kind) {}
    public record LearnRequest(List<LearnEntry> entries, String scope) {}
    public record LearnResult(int learned) {}
    public record DeleteResult(long deleted) {}

    @Transactional(readOnly = true)
    public Candidates candidates(Long userId, Long projectId, String prefix) {
        requireRead(userId, projectId);
        if (prefix != null && prefix.length() > 160) throw new IllegalArgumentException("前缀过长");
        Map<String, Item> result = new LinkedHashMap<>();
        // 阅读项目资料永不写入个人词库；候选获取也不会更新使用次数。
        insights.findTop200ByProjectIdOrderByIdDesc(projectId).forEach(e -> {
            String text = validTextOrNull(e.getName());
            if (text == null) return;
            String kind = "LAW".equals(e.getKind()) && ARTICLE.matcher(text).find() ? "ARTICLE" : e.getKind();
            if (!KINDS.contains(kind)) return;
            result.putIfAbsent(text, new Item("insight:" + e.getId(), text, kind, "insight", "project", e.getId(), null, 0, e.getRetrievalJson() != null && !e.getRetrievalJson().isBlank()));
        });
        projectVariables.findTop500ByProjectIdOrderByUpdatedAtDescIdDesc(projectId).stream().limit(300).forEach(v -> {
            if ("TEXT".equals(v.getType())) addVariable(result, "project-variable:" + v.getId(), v.getValue(), v.getName(), "project");
        });
        userVariables.findTop500ByUserIdOrderByUpdatedAtDescIdDesc(userId).stream().limit(300).forEach(v -> {
            if ("TEXT".equals(v.getType())) addVariable(result, "user-variable:" + v.getId(), v.getValue(), v.getName(), "user");
        });
        // 保留两个范围的独立学习记录供管理；候选展示由前端按文字去重。
        List<Item> learned = new ArrayList<>();
        Set<String> learnedTexts = new HashSet<>();
        for (String scope : List.of("project", "user")) {
            for (CompletionEntryRepository.Summary e : entries.findSummaries(scopeKey(scope, userId, projectId), PageRequest.of(0, 600))) {
                Item known = result.get(e.getText());
                // 明确查询保存的资料优先；只有没有自身缓存时才借用 insight 详情。
                learned.add(new Item("learned:" + e.getId(), e.getText(), e.getKind(), "learned", scope,
                        e.getHasDetail() || known == null ? null : known.entityId(), null, e.getUses(), e.getHasDetail()));
                learnedTexts.add(e.getText());
            }
        }
        result.forEach((text, item) -> { if (!learnedTexts.contains(text)) learned.add(item); });
        String query = prefix == null ? "" : prefix.strip().toLowerCase(Locale.ROOT);
        return new Candidates(learned.stream()
                .filter(i -> query.isEmpty() || i.text().toLowerCase(Locale.ROOT).contains(query))
                .limit(LIMIT).toList(), LIMIT);
    }

    @Transactional
    public LearnResult learn(Long userId, Long projectId, LearnRequest request) {
        requireWrite(userId, projectId);
        if (request == null) throw new IllegalArgumentException("请提供学习条目");
        String key = scopeKey(request.scope(), userId, projectId);
        if (request.entries() == null || request.entries().isEmpty() || request.entries().size() > 50) {
            throw new IllegalArgumentException("每批学习条目须为 1 至 50 条");
        }
        // 整批先校验，避免无事务的调用方也留下部分学习结果；一批中的同名只计一次。
        Map<String, String> validated = new LinkedHashMap<>();
        for (LearnEntry item : request.entries()) {
            if (item == null || item.kind() == null || !KINDS.contains(item.kind())) throw new IllegalArgumentException("不支持的补全类型");
            String text = validTextOrNull(item.text());
            if (text == null) throw new IllegalArgumentException("学习文本须为 2 至 160 字的单行文字");
            validated.putIfAbsent(text, item.kind());
        }
        lockScope(request.scope(), userId, projectId);
        LocalDateTime now = LocalDateTime.now();
        validated.forEach((text, kind) -> {
            CompletionEntry row = entries.findByScopeKeyAndText(key, text).orElseGet(CompletionEntry::new);
            row.setScopeKey(key);
            row.setText(text);
            row.setKind(kind);
            row.setUses(row.getUses() == Long.MAX_VALUE ? Long.MAX_VALUE : row.getUses() + 1);
            row.setLastUsedAt(now);
            entries.save(row);
        });
        entries.flush();
        prune(key);
        return new LearnResult(validated.size());
    }

    /** 明确在线查询才进入既有检索链；成功结果另开短事务缓存，失败保留旧缓存。 */
    public EntityView lookupSelection(Long userId, Long projectId, String kind, String text) {
        requireWrite(userId, projectId);
        EntityView result = insightService.lookupSelection(userId, projectId, kind, text);
        if ("OK".equals(result.retrievalStatus()) && result.detail() != null) {
            self.cacheLookup(userId, projectId, result);
        }
        return result;
    }

    @Transactional
    public void cacheLookup(Long userId, Long projectId, EntityView result) {
        requireWrite(userId, projectId);
        String text = validTextOrNull(result.name());
        if (text == null) return;
        String key = scopeKey("project", userId, projectId);
        lockScope("project", userId, projectId);
        CompletionEntry row = entries.findByScopeKeyAndText(key, text).orElseGet(CompletionEntry::new);
        row.setScopeKey(key);
        row.setText(text);
        row.setKind("LAW".equals(result.kind()) && ARTICLE.matcher(text).find() ? "ARTICLE" : result.kind());
        row.setUses(row.getUses() == Long.MAX_VALUE ? Long.MAX_VALUE : row.getUses() + 1);
        row.setLastUsedAt(LocalDateTime.now());
        try {
            row.setDetailJson(objectMapper.writeValueAsString(result));
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("无法保存查询资料");
        }
        entries.saveAndFlush(row);
        prune(key);
    }

    /** 查看已保存资料只读本地数据库，不刷新也不访问在线查询服务。 */
    @Transactional(readOnly = true)
    public EntityView detail(Long userId, Long projectId, String entryId) {
        requireRead(userId, projectId);
        CompletionEntry row = requireEntry(entryId);
        if (!row.getScopeKey().equals(scopeKey("project", userId, projectId))
                && !row.getScopeKey().equals(scopeKey("user", userId, projectId))) {
            throw new IllegalArgumentException("条目不存在");
        }
        if (row.getDetailJson() == null || row.getDetailJson().isBlank()) throw new IllegalArgumentException("该条目尚无已保存资料");
        try {
            return objectMapper.readValue(row.getDetailJson(), EntityView.class);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("无法读取已保存资料");
        }
    }

    private CompletionEntry requireEntry(String entryId) {
        long id;
        try {
            id = Long.parseLong(entryId.startsWith("learned:") ? entryId.substring(8) : entryId);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("条目不存在");
        }
        return entries.findById(id).orElseThrow(() -> new IllegalArgumentException("条目不存在"));
    }

    private void prune(String key) {
        List<Long> expired = entries.findIds(key, PageRequest.of(1, LIMIT));
        if (!expired.isEmpty()) entries.deleteAllByIdInBatch(expired);
    }

    @Transactional
    public DeleteResult delete(Long userId, Long projectId, String entryId) {
        requireRead(userId, projectId);
        CompletionEntry row = requireEntry(entryId);
        String scope;
        if (row.getScopeKey().equals(scopeKey("user", userId, projectId))) {
            scope = "user";
        } else if (row.getScopeKey().equals(scopeKey("project", userId, projectId))) {
            requireWrite(userId, projectId);
            scope = "project";
        } else {
            throw new IllegalArgumentException("条目不存在");
        }
        lockScope(scope, userId, projectId);
        entries.delete(row);
        return new DeleteResult(1);
    }

    @Transactional
    public DeleteResult clear(Long userId, Long projectId, String scope) {
        requireRead(userId, projectId);
        String key = scopeKey(scope, userId, projectId);
        if ("project".equals(scope)) requireWrite(userId, projectId);
        lockScope(scope, userId, projectId);
        return new DeleteResult(entries.deleteByScopeKey(key));
    }

    private void requireRead(Long userId, Long projectId) {
        if (userId == null) throw new IllegalArgumentException("请先登录");
        if (!members.hasReadPermission(projectId, userId)) throw new IllegalArgumentException("无权限访问该项目");
    }

    private void requireWrite(Long userId, Long projectId) {
        if (userId == null) throw new IllegalArgumentException("请先登录");
        if (!members.hasWritePermission(projectId, userId)) throw new IllegalArgumentException("无权限修改该项目");
    }

    private static String scopeKey(String scope, Long userId, Long projectId) {
        if ("project".equals(scope)) return "p:" + projectId;
        if ("user".equals(scope)) return "u:" + userId;
        throw new IllegalArgumentException("学习范围须为 project 或 user");
    }

    /** 锁住已存在的范围所有者，空词库也能串行 upsert；锁持有到事务提交。 */
    private void lockScope(String scope, Long userId, Long projectId) {
        Object owner = "project".equals(scope)
                ? entityManager.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE)
                : entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (owner == null) throw new IllegalArgumentException("学习范围不存在");
    }

    private static String validTextOrNull(String raw) {
        if (raw == null || raw.length() > 320) return null;
        String text = raw.strip();
        if (text.length() < 2 || text.length() > 160 || text.codePoints().anyMatch(Character::isISOControl)) return null;
        return text;
    }

    private static void addVariable(Map<String, Item> result, String id, String value, String name, String scope) {
        String text = validTextOrNull(value);
        if (text == null) return;
        String kind;
        if (ARTICLE.matcher(text).find()) kind = "ARTICLE";
        else if (text.startsWith("《") && text.endsWith("》")) kind = "LAW";
        else if (text.matches(".*(?:公司|集团|律师事务所|合伙企业)$")) kind = "COMPANY";
        else if (name != null && name.matches(".*(?:姓名|人名|法定代表人|联系人).*")) kind = "PERSON";
        else kind = text.length() > 8 ? "PHRASE" : "WORD";
        result.putIfAbsent(text, new Item(id, text, kind, "variable", scope, null, name, 0, false));
    }
}
