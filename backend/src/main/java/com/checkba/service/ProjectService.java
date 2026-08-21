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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
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
    private final com.checkba.storage.ProjectStorageResolver storageResolver;
    private final com.checkba.version.ProjectRepoService projectRepoService;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectService.class);

    /**
     * 删项目时必须一并清掉的项目级实体。
     *
     * 这些表全部只存一个裸 Long 的 projectId（没有 JPA 关联），而各 profile 都是
     * hibernate ddl-auto=update——裸 Long 列不会生成外键，数据库层也就没有
     * ON DELETE CASCADE。级联只能在这里手写，漏一张表就是一批永久孤儿行。
     * 新增项目级实体时同步往这里加一条。
     */
    private static final List<String> PROJECT_SCOPED_ENTITIES = List.of(
            "ProjectMember", "ProjectFile", "ProjectVariable", "ProjectProfileField",
            "ProjectMemory", "ProjectInvitation", "ProjectRemote", "ProjectTask",
            "ProjectAiMessage");

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
     * 删除项目：连带清掉项目级的库行与磁盘目录。
     *
     * 「删除项目」对律师意味着这个项目的材料不再留存，所以文档目录与版本记录仓库
     * 都要一并抹掉，不能只删 project 行。
     */
    @Transactional
    public void deleteProject(Long id) {
        if (!projectRepository.existsById(id)) {
            throw new IllegalArgumentException(LangText.of("项目不存在: ", "Project not found: ") + id);
        }

        // 物理路径必须在删行之前算：localRoot 存在 Project 行上，行没了就解析不出来。
        // IDE 化本地文件夹项目的目录是用户自己的文件夹，只解绑不删。
        Path projectDir = storageResolver.hasLocalRoot(id) ? null : storageResolver.projectRoot(id);
        Path gitDir = projectRepoService.gitDir(id);

        for (String entity : PROJECT_SCOPED_ENTITIES) {
            entityManager.createQuery("delete from " + entity + " e where e.projectId = :pid")
                    .setParameter("pid", id)
                    .executeUpdate();
        }
        projectRepository.deleteById(id);
        storageResolver.invalidate(id);

        // 磁盘清理失败不回滚：库里已经删干净了，剩下的目录是可再清的垃圾，
        // 为它报错反而会让用户以为项目没删掉。
        deleteDirectoryQuietly(projectDir);
        deleteDirectoryQuietly(gitDir);
    }

    /** 递归删目录，失败只记日志。 */
    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除项目文件失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("删除项目目录失败: {}", dir, e);
        }
    }
}
