package com.checkba.controller;

import com.checkba.service.DdExportService;
import com.checkba.service.LangText;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 尽调交付件导出 REST（dev-board#100 P2）：底稿目录 / 查验计划 / 缺口清单。
 * 鉴权与项目归属校验全部在 {@link DdExportService} 内完成，本层只做「未登录」的整形，
 * 与 {@link EvidenceLinkController} 同一套约定。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/dd-exports")
@RequiredArgsConstructor
public class DdExportController {

    private final DdExportService svc;

    private Long uid(String sessionId) {
        Long u = AuthController.getUserIdFromSession(sessionId);
        if (u == null) throw new IllegalArgumentException(LangText.of("请先登录", "Please sign in first"));
        return u;
    }

    /** kind ∈ docket|verify-plan|gaps；format ∈ docx|xlsx（缺省 docx）。 */
    @GetMapping("/{kind}")
    public DdExportService.ExportResult export(@PathVariable Long projectId,
                                               @PathVariable String kind,
                                               @RequestParam Long docFileId,
                                               @RequestParam(required = false) String format,
                                               @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return svc.export(uid(sessionId), projectId, docFileId, kind, format);
    }
}
