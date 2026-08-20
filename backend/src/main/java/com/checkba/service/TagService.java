package com.checkba.service;

import com.checkba.model.entity.Tag;
import com.checkba.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    /** 标签类型默认色：当事人琥珀褐 / 争议焦点深红褐 / 普通蓝（与 AutoTaggingService 自动标签色一致） */
    private static final String DEFAULT_COLOR_PARTY = "#B45309";
    private static final String DEFAULT_COLOR_ISSUE = "#9B1C31";
    private static final String DEFAULT_COLOR_NORMAL = "#3B82F6";

    /**
     * 校验标签类型白名单：null 或 NORMAL/PARTY/ISSUE；非法值抛异常。
     */
    private static void validateType(String type) {
        if (type == null) {
            return;
        }
        if (!"NORMAL".equals(type) && !"PARTY".equals(type) && !"ISSUE".equals(type)) {
            throw new IllegalArgumentException("非法的标签类型：" + type);
        }
    }

    /**
     * 获取项目的所有标签
     */
    public List<Tag> getProjectTags(Long projectId) {
        return tagRepository.findByProjectId(projectId);
    }

    /**
     * 创建标签
     */
    @Transactional
    public Tag createTag(Long projectId, String name, String color, String description, String type) {
        validateType(type);
        if (tagRepository.existsByProjectIdAndName(projectId, name)) {
            throw new IllegalArgumentException("Tag with this name already exists in the project");
        }

        Tag tag = new Tag();
        tag.setProjectId(projectId);
        tag.setName(name);
        tag.setColor(color);
        tag.setDescription(description);
        tag.setIsSystem(false);
        tag.setType(type);
        tag.setCreatedAt(LocalDateTime.now());
        tag.setUpdatedAt(LocalDateTime.now());

        return tagRepository.save(tag);
    }

    /**
     * 获取或创建标签（供 AI 工具 tag_file 使用）：按 projectId+name 找，找到就原样返回——
     * 同名不同型时复用既有标签、不改型，改型是用户在标签管理里的决定。
     * 没有则创建，isSystem=false，默认色按类型（调用方传了 color 则用调用方的）。
     */
    @Transactional
    public Tag getOrCreateTag(Long projectId, String name, String type, String color) {
        validateType(type);
        return tagRepository.findByProjectIdAndName(projectId, name)
                .orElseGet(() -> {
                    Tag tag = new Tag();
                    tag.setProjectId(projectId);
                    tag.setName(name);
                    tag.setType(type);
                    tag.setColor(color != null ? color : defaultColorForType(type));
                    tag.setIsSystem(false);
                    tag.setCreatedAt(LocalDateTime.now());
                    tag.setUpdatedAt(LocalDateTime.now());
                    return tagRepository.save(tag);
                });
    }

    private static String defaultColorForType(String type) {
        if ("PARTY".equals(type)) {
            return DEFAULT_COLOR_PARTY;
        }
        if ("ISSUE".equals(type)) {
            return DEFAULT_COLOR_ISSUE;
        }
        return DEFAULT_COLOR_NORMAL;
    }
    
    /**
     * 获取或创建系统标签（如 "AI推荐"）
     */
    @Transactional
    public Tag getOrCreateSystemTag(Long projectId, String name, String color) {
        return tagRepository.findByProjectIdAndName(projectId, name)
                .orElseGet(() -> {
                    Tag tag = new Tag();
                    tag.setProjectId(projectId);
                    tag.setName(name);
                    tag.setColor(color);
                    tag.setIsSystem(true); // Mark as system tag
                    tag.setDescription("System generated tag");
                    tag.setCreatedAt(LocalDateTime.now());
                    tag.setUpdatedAt(LocalDateTime.now());
                    return tagRepository.save(tag);
                });
    }

    /**
     * 更新标签
     */
    @Transactional
    public Tag updateTag(Long projectId, Long tagId, String name, String color, String description, String type) {
        validateType(type);
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new IllegalArgumentException("Tag not found"));
        // 归属校验：tagId 必须属于该 projectId，防止本项目成员改动其它项目的标签
        if (!tag.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("标签不属于该项目");
        }

        // check duplicate name if name changed
        if (!tag.getName().equals(name)) {
             if (tagRepository.existsByProjectIdAndName(tag.getProjectId(), name)) {
                throw new IllegalArgumentException("Tag with this name already exists in the project");
            }
        }

        tag.setName(name);
        tag.setColor(color);
        tag.setDescription(description);
        // type 为 null 表示「不改型」而不是清空：前端改型时总是显式传值（含改回 NORMAL），
        // 不带 type 的调用方不该把当事人/争点标签静默抹回普通
        if (type != null) {
            tag.setType(type);
        }
        tag.setUpdatedAt(LocalDateTime.now());

        return tagRepository.save(tag);
    }
    
    /**
     * 删除标签
     */
    @Transactional
    public void deleteTag(Long projectId, Long tagId) {
        Tag tag = tagRepository.findById(tagId).orElse(null);
        if (tag == null) return;
        if (!tag.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("标签不属于该项目");
        }
        tagRepository.deleteById(tagId);
        // Note: Foreign key constraints in FileTag/DB should handle cascade or we should delete manually in FileTagService
        // Ideally we should delete relations in FileTagRepository first? 
        // Let's assume FileTagService/Repository handles cleanup or we do it here if injected.
        // For loose coupling, maybe just delete. If DB has FK cascade, it works.
        // If not, we might need FileTagRepository here to delete relations.
        // Let's rely on database handling or add it if needed later.
    }
}
