package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目档案（客户 / 事项类型 / 立项时间 / 下一步 / 对方）。
 *
 * 对外契约：读接口恒返回 5 条、顺序固定、label 由服务端给——中文文案单一来源在这里，
 * 前端不许自己补齐缺项、不许自己排序、不许再写一份 label 表。
 *
 * source 库里只有 'ai' 与 'user' 两种取值；响应里可能出现的 'default' 是 openedAt
 * 无行时由 Project.createdAt 派生的，永不落库。
 */
@Service
public class ProjectProfileService {

    /** 固定五个字段，顺序即响应顺序 */
    public static final List<String> FIELD_KEYS =
            List.of("client", "matterType", "openedAt", "nextStep", "counterparty");

    private static final Map<String, String> LABELS = Map.of(
            "client", "客户",
            "matterType", "事项类型",
            "openedAt", "立项时间",
            "nextStep", "下一步",
            "counterparty", "对方");

    static final String SOURCE_USER = "user";
    static final String SOURCE_AI = "ai";
    static final String SOURCE_DEFAULT = "default";
    static final String KEY_OPENED_AT = "openedAt";

    private final ProjectProfileFieldRepository repository;
    private final ProjectRepository projectRepository;

    public ProjectProfileService(ProjectProfileFieldRepository repository,
                                 ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    /** 概览页档案头一次渲染完：五个字段全量返回，未填的也返回、值为 null。 */
    public List<Map<String, Object>> getProfile(Long projectId) {
        Map<String, ProjectProfileField> rows = new HashMap<>();
        for (ProjectProfileField row : repository.findByProjectId(projectId)) {
            rows.put(row.getFieldKey(), row);
        }
        Project project = projectRepository.findById(projectId).orElse(null);

        List<Map<String, Object>> fields = new ArrayList<>(FIELD_KEYS.size());
        for (String fieldKey : FIELD_KEYS) {
            fields.add(render(fieldKey, rows.get(fieldKey), project));
        }
        return fields;
    }

    /**
     * 手填单字段（A 期唯一的写入通道）。upsert 语义：
     * 写入即把该字段锁成 source='user'，Plan 2 的 AI 抽取永不覆盖它。
     *
     * value 为 null 或 trim 后为空串 → 删除该行（回到未填态；openedAt 因此回落建档时间）。
     *
     * @Transactional 覆盖读-写两次往返：并发下两个请求都可能查到空 Optional、都走插入，
     * 第二个会撞 (projectId, fieldKey) 唯一约束——由 saveOrRecoverFromRace 兜底成更新语义，
     * 不让 DataIntegrityViolationException 冒到 GlobalExceptionHandler 变成"服务器内部错误"。
     */
    @Transactional
    public Map<String, Object> saveUserField(Long projectId, String fieldKey, String value) {
        requireKnownKey(fieldKey);
        Project project = projectRepository.findById(projectId).orElse(null);

        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            repository.findByProjectIdAndFieldKey(projectId, fieldKey).ifPresent(repository::delete);
            return render(fieldKey, null, project);
        }

        ProjectProfileField row = repository.findByProjectIdAndFieldKey(projectId, fieldKey)
                .orElseGet(() -> newRow(projectId, fieldKey));
        row.setFieldValue(trimmed);
        row.setSource(SOURCE_USER);
        // 改成手填就把 AI 那次判断的痕迹清掉——留着会让 UI 把手填值标成「模型猜的」
        row.setConfidence(null);
        row.setEvidence(null);
        return render(fieldKey, saveOrRecoverFromRace(projectId, fieldKey, row), project);
    }

    /**
     * 已经有人抢先插了同一 (projectId, fieldKey) 时，把本次要写的值/source/confidence/evidence
     * 转移到既存那一行上再存——保留它已有的 uid，不用本次生成的那个。
     */
    private ProjectProfileField saveOrRecoverFromRace(Long projectId, String fieldKey, ProjectProfileField row) {
        try {
            return repository.save(row);
        } catch (DataIntegrityViolationException raceLost) {
            ProjectProfileField existing = repository.findByProjectIdAndFieldKey(projectId, fieldKey)
                    .orElseThrow(() -> raceLost);
            existing.setFieldValue(row.getFieldValue());
            existing.setSource(row.getSource());
            existing.setConfidence(row.getConfidence());
            existing.setEvidence(row.getEvidence());
            return repository.save(existing);
        }
    }

    /** 新行必须自带 uid：跨机器身份只认它，既有行的 uid 任何时候都不许换。 */
    private ProjectProfileField newRow(Long projectId, String fieldKey) {
        ProjectProfileField row = new ProjectProfileField();
        row.setProjectId(projectId);
        row.setFieldKey(fieldKey);
        row.setUid(UUID.randomUUID().toString());
        return row;
    }

    private void requireKnownKey(String fieldKey) {
        if (!FIELD_KEYS.contains(fieldKey)) {
            throw new IllegalArgumentException("未知的档案字段");
        }
    }

    /**
     * 组装单个字段的响应元素。
     *
     * 用 LinkedHashMap 不用 Map.of——Map.of 不接受 null value，而未填的字段五个值全是 null。
     */
    Map<String, Object> render(String fieldKey, ProjectProfileField row, Project project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fieldKey", fieldKey);
        out.put("label", LABELS.get(fieldKey));

        if (row == null) {
            // openedAt 无行时回落建档时间。'default' 只出现在响应里，库里永远不会有这个值。
            if (KEY_OPENED_AT.equals(fieldKey) && project != null && project.getCreatedAt() != null) {
                out.put("fieldValue", project.getCreatedAt().toLocalDate().toString());
                out.put("source", SOURCE_DEFAULT);
            } else {
                out.put("fieldValue", null);
                out.put("source", null);
            }
            out.put("confidence", null);
            out.put("evidence", null);
            out.put("updatedAt", null);
            return out;
        }

        out.put("fieldValue", row.getFieldValue());
        out.put("source", row.getSource());
        out.put("confidence", row.getConfidence());
        out.put("evidence", row.getEvidence());
        out.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return out;
    }
}
