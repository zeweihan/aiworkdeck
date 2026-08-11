package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
