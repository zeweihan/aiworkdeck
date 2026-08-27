package com.checkba.controller;

import com.checkba.service.insight.DocInsightService;
import com.checkba.service.insight.DocInsightViews.EntityView;
import com.checkba.service.insight.DocInsightViews.InsightView;
import com.checkba.service.insight.DocInsightViews.StartResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档「解析」与「依据」窗格的 REST（dev-board#181/#182）。
 *
 * <p>鉴权与 IDOR 全在 {@link DocInsightService} 内（读走 hasReadPermission、
 * 写走 hasWritePermission、跨项目的 id 一律当作不存在），本层只做
 * 「未登录 → 请先登录（GlobalExceptionHandler 转 200 + code=4010）」与参数整形——
 * 与 {@link EvidenceLinkController} 同一形制。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/insight")
@RequiredArgsConstructor
public class DocInsightController {

    private final DocInsightService svc;

    private Long uid(String sessionId) {
        Long u = AuthController.getUserIdFromSession(sessionId);
        if (u == null) throw new IllegalArgumentException("请先登录");
        return u;
    }

    /** 发起解析（异步）。返回时 run 已落库为 RUNNING，前端可立刻轮询 GET。 */
    @PostMapping("/parse")
    public StartResult parse(@PathVariable Long projectId,
                             @RequestBody ParseReq req,
                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.startParse(uid(sessionId), projectId, req == null ? null : req.getDocFileId());
    }

    /** 最近一次解析的结果：run + 实体（不含检索详情）+ 全部发现（含 detail）。 */
    @GetMapping
    public InsightView latest(@PathVariable Long projectId,
                              @RequestParam Long docFileId,
                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.latest(uid(sessionId), projectId, docFileId);
    }

    /** 单个实体的检索详情（列表里刻意不带，见 DocInsightViews 注释）。 */
    @GetMapping("/entities/{entityId}")
    public EntityView entity(@PathVariable Long projectId,
                             @PathVariable Long entityId,
                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.entityDetail(uid(sessionId), projectId, entityId);
    }

    /** 重新检索一个实体（绕过 7 天缓存）。花外部库额度，要写权限。 */
    @PostMapping("/entities/{entityId}/refresh")
    public EntityView refresh(@PathVariable Long projectId,
                              @PathVariable Long entityId,
                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.refreshEntity(uid(sessionId), projectId, entityId);
    }

    @Data
    public static class ParseReq {
        private Long docFileId;
    }
}
