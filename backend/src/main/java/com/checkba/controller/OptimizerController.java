package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.optimizer.OptimizerAgentService;
import com.checkba.service.optimizer.OptimizerMailer;
import com.checkba.service.optimizer.OptimizerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优化者的手动触发与状态查询（管理员）。
 *
 * <p>触发是**异步**的：一轮可能要跑几十分钟（编码 Agent + 开 PR），
 * 挂在 HTTP 请求上必然超时。触发后用 {@code GET /api/optimizer/status} 看进展。
 */
@Slf4j
@RestController
@RequestMapping("/api/optimizer")
@RequiredArgsConstructor
public class OptimizerController {

    private final OptimizerAgentService optimizerAgentService;
    private final OptimizerProperties props;
    private final OptimizerMailer mailer;
    private final UserFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (requireAdmin(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("需要管理员权限"));
        }
        if (!props.isEnabled()) {
            return ResponseEntity.ok(error("优化者未启用（optimizer.enabled=false）"));
        }
        if (optimizerAgentService.isRunning()) {
            return ResponseEntity.ok(error("已有一轮在跑"));
        }
        Thread t = new Thread(optimizerAgentService::runOnce, "optimizer-manual-run");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.ok(success(Map.of("started", true)));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (requireAdmin(sessionId) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("需要管理员权限"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", props.isEnabled());
        data.put("dryRun", props.isDryRun());
        data.put("cron", props.getCron());
        data.put("running", optimizerAgentService.isRunning());
        data.put("lastRunAt", optimizerAgentService.lastRunAt() == null ? null
                : optimizerAgentService.lastRunAt().toString());
        data.put("mailReady", mailer.isAvailable());
        data.put("mailIssue", mailer.unavailableReason());
        data.put("repoPath", props.getRepo().getPath());
        data.put("pending", feedbackRepository.countByStatus(UserFeedback.STATUS_NEW));

        OptimizerAgentService.RunReport r = optimizerAgentService.lastReport();
        if (r != null) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("picked", r.picked());
            report.put("prOpened", r.prOpened());
            report.put("emailed", r.emailed());
            report.put("skipped", r.skipped());
            report.put("failed", r.failed());
            report.put("note", r.note());
            List<Map<String, Object>> items = new ArrayList<>();
            for (OptimizerAgentService.ItemResult it : r.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("feedbackId", it.feedbackId());
                m.put("verdict", it.verdict());
                m.put("confidence", it.confidence());
                m.put("outcome", it.outcome());
                m.put("detail", it.detail());
                items.add(m);
            }
            report.put("items", items);
            data.put("lastReport", report);
        }
        return ResponseEntity.ok(success(data));
    }

    private User requireAdmin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return null;
        return userRepository.findById(userId).filter(adminAccessService::isAdmin).orElse(null);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 1);
        r.put("message", message);
        r.put("data", new HashMap<>());
        return r;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("message", "OK");
        r.put("data", data);
        return r;
    }
}
