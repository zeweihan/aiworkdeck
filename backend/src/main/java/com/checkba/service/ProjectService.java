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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final com.checkba.repository.ProjectFileRepository projectFileRepository;
    private final com.checkba.repository.ProjectProfileFieldRepository profileFieldRepository;
    private final TushareService tushareService;
    private final ProjectVariableService projectVariableService;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    @Transactional
    public Project createProject(ProjectCreateRequest request, Long userId) {
        if (!StringUtils.hasText(request.getProjectType())) {
            throw new IllegalArgumentException(LangText.of("项目类型不能为空", "Project type must not be empty"));
        }

        // 如果不是空白项目，则校验公司名称
        boolean isBlankProject = "BLANK".equalsIgnoreCase(request.getProjectType());
        if (!isBlankProject) {
            if (!StringUtils.hasText(request.getListedCompanyName())) {
                // 部分项目类型可能不需要标的公司，但目前大多数都需要上市公司
                 throw new IllegalArgumentException(LangText.of("上市公司名称不能为空", "Listed company name must not be empty"));
            }
            // 某些类型可能不需要标的公司，这里暂时保持原有逻辑，或者根据类型判断
            // if (!StringUtils.hasText(request.getTargetCompanyName())) {
            //    throw new IllegalArgumentException("标的公司名称不能为空");
            // }
        }

        if (userId == null) {
            throw new IllegalArgumentException(LangText.of("用户 ID 不能为空", "User ID must not be empty"));
        }

        Project project = new Project();

        String name = request.getName();
        if (!StringUtils.hasText(name)) {
            if (isBlankProject) {
                name = LangText.of("未命名项目", "Untitled Project");
            } else {
                // 默认项目名：{上市公司名称} - {标的公司名称} 项目
                String target = request.getTargetCompanyName();
                name = request.getListedCompanyName() + (StringUtils.hasText(target) ? " - " + target : "") + LangText.of(" 项目", " Project");
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
            throw new IllegalArgumentException(LangText.of("项目名称不能为空", "Project name must not be empty"));
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
            throw new IllegalArgumentException(LangText.of("用户 ID 不能为空", "User ID must not be empty"));
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

        // 「最近修改」：一次 group by 拿全部项目的最近文件活动时间。
        // 不能用 Project.updatedAt——那一列只在建项目与改项目名时写过（见 DTO 注释）。
        Map<Long, LocalDateTime> lastActivity = new HashMap<>();
        if (!projects.isEmpty()) {
            List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());
            for (Object[] row : projectFileRepository.findLastActivityByProjectIds(ids)) {
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    lastActivity.put((Long) row[0], (LocalDateTime) row[1]);
                }
            }
        }

        // 项目档案：一次 IN 取完，内存里按项目分组。列表页的「客户」列与「详情」
        // 开关里那四项都从这里来。逐项目调 ProjectProfileService.getProfile 会把
        // 五个字段各查一遍，给已经 N+1 的列表页再加一层。
        Map<Long, Map<String, String>> profiles = new HashMap<>();
        if (!projects.isEmpty()) {
            List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());
            for (com.checkba.model.entity.ProjectProfileField row : profileFieldRepository.findByProjectIdIn(ids)) {
                String v = row.getFieldValue();
                if (v == null || v.isBlank()) continue;   // 只带已填的，未填的键直接不出现
                profiles.computeIfAbsent(row.getProjectId(), k -> new HashMap<>()).put(row.getFieldKey(), v);
            }
        }

        // Batch fetch managers to avoid N+1 (optimization for later, simple loop for now)
        return projects.stream().map(p -> {
            ProjectCardDTO dto = new ProjectCardDTO();
            BeanUtils.copyProperties(p, dto);
            // 一个文件都没有的空项目回落到项目自身的 updatedAt，不留空
            dto.setLastActivityAt(lastActivity.getOrDefault(p.getId(), p.getUpdatedAt()));
            // 一个字段都没填过就是空 map，由前端决定回落到推断值还是整条不渲染
            dto.setProfile(profiles.getOrDefault(p.getId(), Map.of()));
            
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
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("项目不存在: ", "Project not found: ") + id));
    }

    /**
     * 删除项目
     */
    public void deleteProject(Long id) {
        if (!projectRepository.existsById(id)) {
            throw new IllegalArgumentException(LangText.of("项目不存在: ", "Project not found: ") + id);
        }
        projectRepository.deleteById(id);
    }
}
