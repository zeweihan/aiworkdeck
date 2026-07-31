package com.checkba.version.cloud;

import com.checkba.service.DeviceTokenService;
import com.checkba.service.ProjectMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Git 端点鉴权：Basic 的 password 位是设备令牌（username 位仅展示）。
 * 读=项目成员且非 CLIENT；写=hasWritePermission 且非 CLIENT——
 * 与 VersionController.requireMember 拒 CLIENT 同一口径。
 * 参数序 (projectId, userId)：两参同为 Long，写反能编译（v1 地雷 #3）。
 */
@Service
public class GitAccessService {

    private final DeviceTokenService deviceTokenService;
    private final ProjectMemberService memberService;

    public GitAccessService(DeviceTokenService deviceTokenService,
                            ProjectMemberService memberService) {
        this.deviceTokenService = deviceTokenService;
        this.memberService = memberService;
    }

    public Long authorize(HttpServletRequest request, long projectId, boolean write) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            throw new GitAccessDeniedException(401);
        }
        String token;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            token = colon < 0 ? decoded : decoded.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            throw new GitAccessDeniedException(401);
        }
        Long userId = deviceTokenService.resolveUserId(token);
        if (userId == null) throw new GitAccessDeniedException(401);
        if (memberService.isClient(projectId, userId)) throw new GitAccessDeniedException(403);
        boolean allowed = write
                ? memberService.hasWritePermission(projectId, userId)
                : memberService.hasReadPermission(projectId, userId);
        if (!allowed) throw new GitAccessDeniedException(403);
        return userId;
    }
}
