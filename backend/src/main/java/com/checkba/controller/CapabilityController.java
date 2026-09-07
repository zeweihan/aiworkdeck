package com.checkba.controller;

import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.LangText;
import com.checkba.service.capability.CapabilityInstallService;
import com.checkba.service.capability.CapabilitySlotRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力槽与能力包接口（设计稿 docs/superpowers/specs/2026-09-07-capability-slots-self-upgrade-design.md）：
 * <pre>
 * GET  /api/capabilities                  槽 + 候选 + 当前选择 + 开发者模式
 * POST /api/capabilities/{slot}/select    {ref}
 * POST /api/capabilities/{slot}/rollback
 * POST /api/capabilities/plan             {url}   只拉取与校验，返回安装计划
 * POST /api/capabilities/apply            {planId} 落盘并安装
 * GET/PUT /api/capabilities/dev-mode      开发者模式开关
 * </pre>
 *
 * 鉴权与 {@code PluginDevController} 同口径：一律 admin（桌面单机全员管理员）。
 * 业务错误一律 {@code {code:1, message}}——校验失败不是掉线，绝不带 4010。
 */
@RestController
@RequestMapping("/api/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilitySlotRegistry slotRegistry;
    private final CapabilityInstallService installService;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        List<Map<String, Object>> slots = new ArrayList<>();
        for (CapabilitySlotRegistry.SlotDef def : slotRegistry.slots()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", def.id());
            row.put("name", def.name());
            row.put("protocol", def.protocol());
            row.put("kind", def.kind());
            row.put("selected", slotRegistry.effectiveRef(def.id()));
            row.put("previous", slotRegistry.previousRef(def.id()));
            row.put("degraded", slotRegistry.isDegraded(def.id()));
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (CapabilitySlotRegistry.Candidate c : slotRegistry.candidates(def.id())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ref", c.ref());
                item.put("source", c.source());
                item.put("label", c.label());
                item.put("available", c.available());
                item.put("unsigned", c.unsigned());
                item.put("reason", c.reason());
                candidates.add(item);
            }
            row.put("candidates", candidates);
            slots.add(row);
        }
        Map<String, Object> result = ok();
        result.put("slots", slots);
        result.put("devMode", slotRegistry.devMode());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{slot}/select")
    public ResponseEntity<Map<String, Object>> select(
            @PathVariable("slot") String slot,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        try {
            String ref = body == null || body.get("ref") == null ? null : String.valueOf(body.get("ref"));
            slotRegistry.select(slot, ref);
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/{slot}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable("slot") String slot,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        try {
            slotRegistry.rollback(slot);
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        try {
            String url = body == null || body.get("url") == null ? "" : String.valueOf(body.get("url"));
            Map<String, Object> result = ok();
            result.put("plan", installService.plan(url));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        try {
            String planId = body == null || body.get("planId") == null ? "" : String.valueOf(body.get("planId"));
            Map<String, Object> result = ok();
            result.put("id", installService.apply(planId));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(error(e.getMessage()));
        }
    }

    @GetMapping("/dev-mode")
    public ResponseEntity<Map<String, Object>> devMode(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        Map<String, Object> result = ok();
        result.put("enabled", slotRegistry.devMode());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/dev-mode")
    public ResponseEntity<Map<String, Object>> setDevMode(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (!isAdmin(sessionId)) {
            return forbidden();
        }
        boolean on = body != null && Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        slotRegistry.setDevMode(on);
        Map<String, Object> result = ok();
        result.put("enabled", on);
        return ResponseEntity.ok(result);
    }

    private boolean isAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId).map(adminAccessService::isAdmin).orElse(false);
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(LangText.of("仅管理员可操作", "Administrator permission required")));
    }

    private static Map<String, Object> ok() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        return result;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
