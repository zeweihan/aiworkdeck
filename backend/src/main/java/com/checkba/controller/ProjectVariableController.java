package com.checkba.controller;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/variables")
public class ProjectVariableController {

    @Autowired
    private ProjectVariableService service;

    @Autowired
    private ProjectMemberService projectMemberService;

    // 越权校验：此前 getVariables/deleteVariable 无鉴权、saveVariable 允许匿名。
    private Long requireMember(String sessionId, Long projectId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该资源", "You don't have permission to access this resource"));
        }
        return userId;
    }

    /**
     * 变量的对外形态。字段与 {@link ProjectVariable} 逐一对应，JSON 形状一字不变。
     *
     * <p>为什么不直接回实体：{@code creatorName} 是**写时快照**——建变量那一刻把
     * {@code User.displayName} 抄了一份存进 project_variables，单机模式下抄到的就是中文
     * 哨兵「本机用户」，英文界面照样显示中文（dev-board#351）。同族的读时本地化那一招
     * （{@code LocalIdentityService.displayNameOf}）要落在这个字段上，而实体在 OSIV 会话里
     * 是受管对象：就地改它一旦被 Hibernate 脏检查刷回库，库里存的就成了「Local user」，
     * 中文界面反过来看到英文，是不可逆的数据污染。多一个只读视图，改动只落在出参上。
     * 同族已修的另外几处（/api/auth/me、项目成员、项目列表 managerName）本来就是往新
     * 对象里 put，天然没有这个问题——这里补齐的是唯一一处直接回实体的。
     */
    public record VariableView(
            Long id,
            Long projectId,
            String name,
            String value,
            String variableGroup,
            String resolvedValue,
            String type,
            Long creatorId,
            String creatorName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static VariableView of(ProjectVariable v) {
            return new VariableView(
                    v.getId(),
                    v.getProjectId(),
                    v.getName(),
                    v.getValue(),
                    v.getVariableGroup(),
                    v.getResolvedValue(),
                    v.getType(),
                    v.getCreatorId(),
                    // 库里恒存中文哨兵，按当前界面语言替换；真实用户名一个字都不动
                    LocalIdentityService.displayNameOf(v.getCreatorName()),
                    v.getCreatedAt(),
                    v.getUpdatedAt());
        }
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<VariableView>> getVariables(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return ResponseEntity.ok(service.getVariablesByProject(projectId).stream()
                .map(VariableView::of)
                .toList());
    }

    @PostMapping
    public ResponseEntity<?> saveVariable(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody ProjectVariable variable
    ) {
        Long userId = requireMember(sessionId, variable.getProjectId());
        variable.setCreatorId(userId);
        if (variable.getCreatorName() == null) {
            String username = AuthController.getUsernameFromSession(sessionId);
            variable.setCreatorName(username != null ? username : "Unknown");
        }
        return ResponseEntity.ok(VariableView.of(service.createOrUpdateVariable(variable)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVariable(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, service.getProjectIdById(id));
        service.deleteVariable(id);
        return ResponseEntity.ok().build();
    }
}
