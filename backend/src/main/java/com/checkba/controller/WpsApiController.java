package com.checkba.controller;

import com.checkba.exception.FeatureNotConfiguredException;
import com.checkba.service.WpsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * WPS API 控制器
 * 提供生成编辑链接、会话 token 等接口给前端调用
 */
@RestController
@RequestMapping("/api/wps")
@CrossOrigin(origins = "*")
public class WpsApiController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WpsApiController.class);

    /**
     * 默认用户 ID（需与 WPS 控制台“人员配置”中的账号 ID 保持一致）
     */
    private static final String DEFAULT_USER_ID = "1780305141";

    /** WPS 未配置时的双语提示，前端据 code=4001 降级为只读预览（#18 T6）。 */
    private static final String WPS_NOT_CONFIGURED_MSG =
            "文档编辑未配置：请在设置中配置 WPS WebOffice / "
                    + "Document editing is not configured: set up WPS WebOffice in Settings.";

    @Autowired
    private WpsService wpsService;

    /**
     * 生成 WPS 编辑链接（URL 直连方案预留）
     * POST /api/wps/generate-url
     */
    @PostMapping("/generate-url")
    public ResponseEntity<Map<String, Object>> generateEditUrl(@RequestBody Map<String, String> request) {
        String fileId = request.get("fileId");
        String fileName = request.get("fileName");
        String mode = request.getOrDefault("mode", "edit");

        if (fileId == null || fileId.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "fileId is required");
            return ResponseEntity.badRequest().body(error);
        }

        if (!wpsService.isConfigured()) {
            throw new FeatureNotConfiguredException("wps", WPS_NOT_CONFIGURED_MSG);
        }

        String editUrl = wpsService.generateEditUrl(fileId, fileName, mode);

        Map<String, Object> result = new HashMap<>();
        result.put("url", editUrl);
        result.put("fileId", fileId);
        result.put("mode", mode);

        return ResponseEntity.ok(result);
    }

    /**
     * 获取回调网关地址（用于前端配置）
     * GET /api/wps/callback-base-url
     */
    @GetMapping("/callback-base-url")
    public ResponseEntity<Map<String, String>> getCallbackBaseUrl() {
        Map<String, String> result = new HashMap<>();
        result.put("callbackBaseUrl", wpsService.getCallbackBaseUrl());
        return ResponseEntity.ok(result);
    }

    /**
     * 创建前端 JS SDK 会话，生成业务 token
     * POST /api/wps/session
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, String> request) {
        String fileId = request.get("fileId");
        String userId = request.getOrDefault("userId", DEFAULT_USER_ID);
        if (userId == null || "null".equals(userId)) {
            userId = DEFAULT_USER_ID;
        }

        if (fileId == null || fileId.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "fileId is required");
            return ResponseEntity.badRequest().body(error);
        }

        if (!wpsService.isConfigured()) {
            throw new FeatureNotConfiguredException("wps", WPS_NOT_CONFIGURED_MSG);
        }

        long timestamp = System.currentTimeMillis() / 1000;
        String token = wpsService.generateSessionToken(fileId.trim(), userId, timestamp);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("fileId", fileId.trim());
        result.put("userId", userId);
        result.put("timestamp", timestamp);

        log.info("Generated WPS session token for fileId={}, userId={}", fileId, userId);

        return ResponseEntity.ok(result);
    }

    /**
     * 生成调试 Token（用于 WPS 控制台手动调试）
     * GET /api/wps/debug-token
     */
    @GetMapping("/debug-token")
    public ResponseEntity<Map<String, Object>> generateDebugToken() {
        String debugToken = "debug_token_" + System.currentTimeMillis();
        long timestamp = System.currentTimeMillis() / 1000;

        Map<String, Object> result = new HashMap<>();
        result.put("token", debugToken);
        result.put("timestamp", timestamp);
        result.put("fileId", "project_3_doc_1");
        result.put("callbackUrl", wpsService.getCallbackBaseUrl() + "/v3/3rd/files/project_3_doc_1");
        result.put("usage", "在WPS控制台的接口调试功能中使用：\n" +
                "1. token字段：填入上面的token值（或任意字符串）\n" +
                "2. user_query字段：可以留空或填入查询参数\n" +
                "3. file_id字段：填入要测试的文件ID（如project_3_doc_1）");
        result.put("note", "根据WPS文档，token可以是任意字符串，用于业务方自定义鉴权");

        return ResponseEntity.ok(result);
    }
}
