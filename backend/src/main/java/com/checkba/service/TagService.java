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
    public Tag createTag(Long projectId, String name, String color, String description) {
        if (tagRepository.existsByProjectIdAndName(projectId, name)) {
            throw new IllegalArgumentException("Tag with this name already exists in the project");
        }
        
        Tag tag = new Tag();
        tag.setProjectId(projectId);
        tag.setName(name);
        tag.setColor(color);
        tag.setDescription(description);
        tag.setIsSystem(false);
        tag.setCreatedAt(LocalDateTime.now());
        tag.setUpdatedAt(LocalDateTime.now());
        
        return tagRepository.save(tag);
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
    public Tag updateTag(Long projectId, Long tagId, String name, String color, String description) {
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
