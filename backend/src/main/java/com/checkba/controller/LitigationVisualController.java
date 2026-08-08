package com.checkba.controller;

import com.checkba.service.LitigationVisualPanelService;
import com.checkba.service.ProjectMemberService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 「诉讼可视化」面板的接口。
 * 鉴权模式与 DdController / ShareholderMeetingController 一致（X-Session-Id → 项目成员校验）。
 */
@RestController
@RequestMapping("/api/litigation-visual")
@RequiredArgsConstructor
public class LitigationVisualController {

    private final LitigationVisualPanelService panelService;
    private final ProjectMemberService projectMemberService;

    private void requireMember(String sessionId, Long projectId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该资源");
        }
    }

    /**
     * 出图环境自检。前端据此做降级提示。
     * 不要项目上下文——它问的是"这台机器行不行"，与卷宗无关，登录即可。
     */
    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            throw new IllegalArgumentException("未登录");
        }
        return panelService.status();
    }

    @GetMapping("/projects/{projectId}/diagrams")
    public List<LitigationVisualPanelService.DiagramView> diagrams(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return panelService.listDiagrams(projectId);
    }

    /** 换视觉模式重画。内容不变，只有表层变——所以是按钮，不是一轮对话。 */
    @PostMapping("/projects/{projectId}/restyle")
    public Map<String, Object> restyle(
            @PathVariable Long projectId,
            @RequestBody RestyleDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return panelService.restyle(projectId, dto.getFolderId(), dto.getMode());
    }

    /**
     * 生成「开始出图」要发给 AI 的那句话。前端拿到后经 ChatInterface 以 AGENT 模式发出。
     * 由服务端拼是因为触发词必须原样在正文里才能命中 skill 注入。
     */
    @PostMapping("/projects/{projectId}/kickoff")
    public Map<String, String> kickoff(
            @PathVariable Long projectId,
            @RequestBody KickoffDto dto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return Map.of("prompt", panelService.buildKickoffPrompt(dto.getScope(), dto.getDiagramHint()));
    }

    @Data
    public static class RestyleDto {
        private Long folderId;
        private String mode;
    }

    @Data
    public static class KickoffDto {
        /** 材料范围的人类描述（文件夹名 / 选中的文件名列表），留空 = 全项目 */
        private String scope;
        /** 用户在面板里点的图种，留空 = 让 AI 自己判断 */
        private String diagramHint;
    }
}
