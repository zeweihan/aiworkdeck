package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.AnchorReport;
import com.checkba.service.evidence.EvidenceLinkViews.AnchorReportResult;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.checkba.service.evidence.EvidenceVerifyService;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchQuery;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchResult;
import com.checkba.service.evidence.EvidenceVerifyViews.LinkVerdict;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 证据链接 REST（spec §2.2）。鉴权与 IDOR 全部在 EvidenceLinkService 内完成（读 hasReadPermission、写 hasWritePermission），
 * 本层只做「未登录 → 请先登录（4010 信封）」与参数整形。
 *
 * 路径变量 linkKey 限定 [A-Za-z0-9_]+，所以 /ref-counts、/anchors/report、/targets/{id} 不会被 /{linkKey} 吃掉。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/evidence-links")
@RequiredArgsConstructor
public class EvidenceLinkController {

    private static final String KEY = "{linkKey:[A-Za-z0-9_]+}";

    private final EvidenceLinkService svc;
    private final EvidenceVerifyService verifySvc;
    private final ProjectMemberService projectMemberService;

    private Long uid(String sessionId) {
        Long u = AuthController.getUserIdFromSession(sessionId);
        if (u == null) throw new IllegalArgumentException("请先登录");
        return u;
    }

    @PostMapping
    public LinkView create(@PathVariable Long projectId,
                           @RequestBody CreateReq r,
                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.create(uid(sessionId), projectId, r.getDocFileId(), r.getLinkKey(), r.getAnchorText(),
                r.getSectionPath(), r.getSectionTitle(), r.getCreatedByKind() == null ? "human" : r.getCreatedByKind(),
                r.getTargets());
    }

    @GetMapping
    public List<LinkView> list(@PathVariable Long projectId,
                               @RequestParam(required = false) Long docFileId,
                               @RequestParam(required = false) Long fileId,
                               @RequestParam(required = false) Long partyTagId,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String sectionPath,
                               @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long u = uid(sessionId);
        if (fileId != null) return svc.listByFile(u, projectId, fileId);
        if (docFileId == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 或 fileId 必填", "docFileId or fileId required"));
        }
        if (partyTagId != null) return svc.listByParty(u, projectId, docFileId, partyTagId);
        return svc.listByDoc(u, projectId, docFileId, status, sectionPath);
    }

    @GetMapping("/ref-counts")
    public Map<Long, Long> refCounts(@PathVariable Long projectId,
                                     @RequestParam List<Long> fileIds,
                                     @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long u = uid(sessionId);
        if (!projectMemberService.hasReadPermission(projectId, u)) {
            throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project"));
        }
        return svc.refCounts(projectId, fileIds);
    }

    @GetMapping("/" + KEY)
    public LinkView getByKey(@PathVariable Long projectId,
                             @PathVariable String linkKey,
                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.getByKey(uid(sessionId), projectId, linkKey);
    }

    /** body 是 TargetInput 数组；可选 ?createdByKind=human|ai|plugin（默认 human）记在每条 target 上。 */
    @PostMapping("/" + KEY + "/targets")
    public LinkView addTargets(@PathVariable Long projectId,
                               @PathVariable String linkKey,
                               @RequestBody List<TargetInput> targets,
                               @RequestParam(required = false) String createdByKind,
                               @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.addTargets(uid(sessionId), projectId, linkKey, targets, createdByKind);
    }

    @PatchMapping("/targets/{targetId}")
    public TargetView updateTarget(@PathVariable Long projectId,
                                   @PathVariable Long targetId,
                                   @RequestBody TargetInput patch,
                                   @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.updateTarget(uid(sessionId), projectId, targetId, patch);
    }

    @DeleteMapping("/targets/{targetId}")
    public Map<String, Object> removeTarget(@PathVariable Long projectId,
                                            @PathVariable Long targetId,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        svc.removeTarget(uid(sessionId), projectId, targetId);
        return Map.of("success", true);
    }

    @DeleteMapping("/" + KEY)
    public Map<String, Object> delete(@PathVariable Long projectId,
                                      @PathVariable String linkKey,
                                      @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        svc.delete(uid(sessionId), projectId, linkKey);
        return Map.of("success", true);
    }

    /** worker check_link_anchors 的结果回写：{docFileId, reports:[{linkKey, exists, text}]} → {changed:[linkKey], ignored:N}（exists 缺失的条目不改状态、计入 ignored）。 */
    @PostMapping("/anchors/report")
    public Map<String, Object> reportAnchors(@PathVariable Long projectId,
                                             @RequestBody ReportReq r,
                                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (r.getDocFileId() == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 必填", "docFileId required"));
        }
        AnchorReportResult res = svc.reportAnchors(uid(sessionId), projectId, r.getDocFileId(), r.getReports());
        return Map.of("changed", res.changed(), "ignored", res.ignored());
    }

    /** 用户「保留关联」，body {text} = 当前锚点文字。 */
    @PostMapping("/" + KEY + "/keep")
    public LinkView keepAnchor(@PathVariable Long projectId,
                               @PathVariable String linkKey,
                               @RequestBody(required = false) KeepReq r,
                               @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.keepAnchor(uid(sessionId), projectId, linkKey, r == null ? null : r.getText());
    }

    // ---------------------------------------------------------------- 勾稽核查（P2，dev-board#116）

    /** 核查单条：陈述 ↔ 底稿一致性判定，结论落到各 target 的 relation/confidence/verify_json。 */
    @PostMapping("/" + KEY + "/verify")
    public LinkVerdict verifyLink(@PathVariable Long projectId,
                                  @PathVariable String linkKey,
                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return verifySvc.verifyLink(uid(sessionId), projectId, linkKey);
    }

    /**
     * 批量核查：body {docFileId, sectionPath?, status?, offset?, limit?}。
     * 单次上限 {@link EvidenceVerifyService#MAX_BATCH_LINKS}，撞上限/超时/被取消都会回 nextOffset，原样再调即续跑。
     */
    @PostMapping("/verify")
    public BatchResult verifyBatch(@PathVariable Long projectId,
                                   @RequestBody(required = false) VerifyBatchReq r,
                                   @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (r == null || r.getDocFileId() == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 必填", "docFileId required"));
        }
        return verifySvc.verifyBatch(uid(sessionId), projectId,
                new BatchQuery(r.getDocFileId(), r.getSectionPath(), r.getStatus(), r.getOffset(), r.getLimit()));
    }

    /** 取消本人在该项目里正在跑的批量核查；没有在跑的回 {cancelled:false}。 */
    @PostMapping("/verify/cancel")
    public Map<String, Object> cancelVerify(@PathVariable Long projectId,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return Map.of("cancelled", verifySvc.cancelBatch(uid(sessionId), projectId));
    }

    /** 用户「重新指定」，body {newLinkKey, anchorText, sectionPath, sectionTitle}。 */
    @PostMapping("/" + KEY + "/rebind")
    public LinkView rebind(@PathVariable Long projectId,
                           @PathVariable String linkKey,
                           @RequestBody RebindReq r,
                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.rebind(uid(sessionId), projectId, linkKey, r.getNewLinkKey(), r.getAnchorText(),
                r.getSectionPath(), r.getSectionTitle());
    }

    @Data
    public static class CreateReq {
        private Long docFileId;
        private String linkKey;
        private String anchorText;
        private String sectionPath;
        private String sectionTitle;
        private String createdByKind;
        private List<TargetInput> targets;
    }

    @Data
    public static class ReportReq {
        private Long docFileId;
        private List<AnchorReport> reports;
    }

    @Data
    public static class KeepReq {
        private String text;
    }

    @Data
    public static class VerifyBatchReq {
        private Long docFileId;
        private String sectionPath;
        private String status;
        private Integer offset;
        private Integer limit;
    }

    @Data
    public static class RebindReq {
        private String newLinkKey;
        private String anchorText;
        private String sectionPath;
        private String sectionTitle;
    }
}
