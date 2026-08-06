package com.checkba.service;

import cn.hutool.json.JSONUtil;
import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.ProjectVariable;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.model.dto.ProjectCardDTO;
import com.checkba.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final TushareService tushareService;
    private final ProjectVariableService projectVariableService;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    @Transactional
    public Project createProject(ProjectCreateRequest request, Long userId) {
        if (!StringUtils.hasText(request.getProjectType())) {
            throw new IllegalArgumentException("项目类型不能为空");
        }
        
        // 如果不是空白项目，则校验公司名称
        boolean isBlankProject = "BLANK".equalsIgnoreCase(request.getProjectType());
        if (!isBlankProject) {
            if (!StringUtils.hasText(request.getListedCompanyName())) {
                // 部分项目类型可能不需要标的公司，但目前大多数都需要上市公司
                 throw new IllegalArgumentException("上市公司名称不能为空");
            }
            // 某些类型可能不需要标的公司，这里暂时保持原有逻辑，或者根据类型判断
            // if (!StringUtils.hasText(request.getTargetCompanyName())) {
            //    throw new IllegalArgumentException("标的公司名称不能为空");
            // }
        }

        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }

        Project project = new Project();

        String name = request.getName();
        if (!StringUtils.hasText(name)) {
            if (isBlankProject) {
                name = "未命名项目";
            } else {
                // 默认项目名：{上市公司名称} - {标的公司名称} 项目
                String target = request.getTargetCompanyName();
                name = request.getListedCompanyName() + (StringUtils.hasText(target) ? " - " + target : "") + " 项目";
            }
        }

        project.setName(name);
        project.setProjectType(request.getProjectType());
        // 公司名列为 NOT NULL：空白项目（或未提供公司名）时请求里可能为 null，默认空串避免插入抛
        // DataIntegrityViolation 导致创建接口 500（前端目前发空串，此处兜底更健壮）。
        project.setListedCompanyName(request.getListedCompanyName() != null ? request.getListedCompanyName() : "");
        project.setTargetCompanyName(request.getTargetCompanyName() != null ? request.getTargetCompanyName() : "");
        project.setUserId(userId);

        if (request.getListedCompanyInfo() != null) {
            project.setListedCompanyInfoJson(JSONUtil.toJsonStr(request.getListedCompanyInfo()));
        }
        if (request.getTargetCompanyInfo() != null) {
            project.setTargetCompanyInfoJson(JSONUtil.toJsonStr(request.getTargetCompanyInfo()));
        }

        LocalDateTime now = LocalDateTime.now();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);

        Project savedProject = projectRepository.save(project);
        telemetryService.record("project.created",
                java.util.Map.of("kind", "managed", "reused", false, "importedCount", 0));

        // Add creator as ADMIN member
        ProjectMember member = new ProjectMember();
        member.setProjectId(savedProject.getId());
        member.setUserId(userId);
        member.setRole("ADMIN");
        projectMemberRepository.save(member);

        // Fetch and save Tushare variables for listed company
        if (!isBlankProject && StringUtils.hasText(request.getListedCompanyName())) {
            try {
                List<ProjectVariable> vars = tushareService.fetchAndCreateVariables(savedProject.getId(), request.getListedCompanyName());
                for (ProjectVariable var : vars) {
                    projectVariableService.createOrUpdateVariable(var);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return savedProject;
    }

    /**
     * 更新项目名称
     */
    public Project updateProjectName(Long id, String newName) {
        if (!StringUtils.hasText(newName)) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        Project project = getProject(id);
        project.setName(newName);
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }


    /**
     * 获取用户的项目列表（包括创建的和加入的）
     */
    public List<Project> getUserProjects(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        
        // 1. Created projects
        List<Project> createdProjects = projectRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // 2. Member projects
        List<Long> memberProjectIds = projectMemberRepository.findByUserId(userId).stream()
                .map(ProjectMember::getProjectId)
                .collect(Collectors.toList());

        List<Project> memberProjects = projectRepository.findAllById(memberProjectIds);

        // Combine and dedup
        Set<Project> allProjects = new HashSet<>(createdProjects);
        allProjects.addAll(memberProjects);

        return allProjects.stream()
                .sorted((p1, p2) -> {
                     if (p1.getCreatedAt() == null || p2.getCreatedAt() == null) return 0;
                     return p2.getCreatedAt().compareTo(p1.getCreatedAt());
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取用户的项目列表（DTO版，包含角色和负责人信息）
     */
    public List<ProjectCardDTO> getUserProjectCardDTOs(Long userId) {
        List<Project> projects = getUserProjects(userId);
        
        // Batch fetch managers to avoid N+1 (optimization for later, simple loop for now)
        return projects.stream().map(p -> {
            ProjectCardDTO dto = new ProjectCardDTO();
            BeanUtils.copyProperties(p, dto);
            
            // Determine Role
            // 1. Check if owner
            if (p.getUserId().equals(userId)) {
                dto.setMyRole("OWNER");
            } else {
                // 2. Check member role
                projectMemberRepository.findByProjectIdAndUserId(p.getId(), userId)
                        .ifPresent(m -> dto.setMyRole(m.getRole()));
            }

            // Determine Manager info
            // Owner is the manager
            userRepository.findById(p.getUserId()).ifPresent(u -> {
                dto.setManagerId(u.getId());
                dto.setManagerName(u.getDisplayName());
                dto.setManagerAvatarUrl(u.getAvatarUrl());
            });

            return dto;
        }).collect(Collectors.toList());
    }

    public Project getProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
    }

    /**
     * 删除项目
     */
    public void deleteProject(Long id) {
        if (!projectRepository.existsById(id)) {
            throw new IllegalArgumentException("项目不存在: " + id);
        }
        projectRepository.deleteById(id);
    }
}
