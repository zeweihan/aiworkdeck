package com.checkba.controller;

import com.checkba.service.LangText;
import com.checkba.service.evidence.webverify.WebVerifyImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 网核 zip 导入 REST（dev-board#100 P3）：用户把外部工具导出的 zip 传进来，服务端解包落盘并自动挂链。
 * <b>服务端不联网抓取</b>（2026-08-21 拍板：网核只留接口）。
 *
 * <p>鉴权与项目归属校验全在 {@link WebVerifyImportService} 里，本层只做「未登录」的整形，
 * 与 {@link DdExportController} / {@link EvidenceLinkController} 同一套约定。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/web-verify")
@RequiredArgsConstructor
public class WebVerifyController {

    private final WebVerifyImportService svc;

    private Long uid(String sessionId) {
        Long u = AuthController.getUserIdFromSession(sessionId);
        if (u == null) throw new IllegalArgumentException(LangText.of("请先登录", "Please sign in first"));
        return u;
    }

    /**
     * @param sites     站点 code，可重复传或逗号分隔；不传 = 包里有什么收什么
     * @param docFileId 报告文件；不传时项目里恰好只有一份带证据关联的文档就用它，否则只落盘不挂链
     */
    @PostMapping("/import")
    public WebVerifyImportService.ImportResult importArchive(
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam String partyName,
            @RequestParam(required = false) String unifiedSocialCreditCode,
            @RequestParam(required = false) List<String> sites,
            @RequestParam(required = false) Long docFileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = uid(sessionId);
        byte[] bytes;
        try {
            bytes = file == null ? null : file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException(LangText.of("读取上传文件失败: ", "Failed to read the uploaded file: ")
                    + e.getMessage(), e);
        }
        return svc.importArchive(userId, projectId, partyName, unifiedSocialCreditCode,
                splitSites(sites), docFileId, bytes, "human");
    }

    /** 允许 {@code sites=a&sites=b} 与 {@code sites=a,b} 两种写法。 */
    static List<String> splitSites(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().flatMap(s -> java.util.Arrays.stream(s.split(","))).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }
}
