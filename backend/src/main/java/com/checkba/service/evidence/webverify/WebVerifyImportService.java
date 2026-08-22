package com.checkba.service.evidence.webverify;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 网核 zip 落盘 + 自动挂链（dev-board#100 P3）。
 *
 * <p>链路：{@link WebVerifyProvider}（本仓只有离线的 {@link ManualWebVerifyProvider}，不联网）
 * → 落进项目 {@code _网核/<主体>/<站点>-<日期>.<ext>} → 对每件材料按规则挂 {@link EvidenceLinkService} 的
 * target。写库只经 EvidenceLinkService 这一个出口（它是证据关联的单一出口，不绕开它直接动两张表）。
 *
 * <p><b>挂链规则（宁可不挂，也不瞎挂）</b>：
 * <ol>
 * <li>候选段落 = 报告 {@code docFileId} 里「已有 method=web_check 的 target」<b>且</b>
 *     「锚点文字/章节路径/章节标题里出现该主体名」的 EvidenceLink。两个条件缺一不可。</li>
 * <li>候选里若有段落提到该站点（中文名或别名），只挂到这些段落；否则挂到全部候选段落。</li>
 * <li>一个候选都没有（或压根定位不到报告文件）→ 只落盘，该件进返回值的
 *     {@link ImportResult#unlinked()}，附上不挂的原因。</li>
 * </ol>
 *
 * <p>落盘用 {@link ProjectFileService.ConflictPolicy#RENAME}：同站点同日重复导入会得到「(1)」副本，
 * <b>不覆盖</b>——网核件是证据，覆盖等于毁证（与 {@code DdExportService} 的交付件「同名就地覆盖」相反，
 * 那边覆盖的是可随时重算的派生物）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebVerifyImportService {

    /** 网核件权威存放目录（spec §1.2「网核截图 = ProjectFile」）。 */
    public static final String WEB_VERIFY_FOLDER = "_网核";

    /** 只挂到已经声明「网络核查」的段落上。 */
    public static final String METHOD_WEB_CHECK = "web_check";

    private static final int NOTE_MAX = 512;
    private static final int PARTY_NAME_MAX = 120;
    /** 文件夹/文件名里不许出现的字符（路径分隔符与 Windows 保留字符）。 */
    private static final String ILLEGAL_NAME_CHARS = "[/\\\\:*?\"<>|\\p{Cntrl}]";

    private final ProjectMemberService projectMemberService;
    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;
    private final StorageServiceFactory storageServiceFactory;
    private final EvidenceLinkService evidenceLinkService;
    private final EvidenceLinkRepository evidenceLinkRepository;
    private final List<WebVerifyProvider> providers;
    private final ObjectMapper objectMapper;

    /** 一件落盘的网核材料。{@code linkedKeys} 为空即「未挂链」，同时会出现在 {@link ImportResult#unlinked()}。 */
    public record LandedItem(Long fileId, String path, String site, String siteLabel, String capturedAt,
                             String sourceUrl, String summary, List<String> linkedKeys) {}

    /** 落了盘但没挂上任何段落的网核件，附原因（给人看的，别让它静默消失）。 */
    public record UnlinkedItem(Long fileId, String path, String site, String reason) {}

    public record ImportResult(String partyName, String provider, Long docFileId, int landed,
                               List<LandedItem> items, List<UnlinkedItem> unlinked) {}

    /**
     * @param siteCodes     要收的站点 code；空 = 包里有什么收什么。写了但不认识的 code 直接报错，
     *                      不能当成「其他」——那会把用户想要的筛选悄悄变成全收
     * @param docFileId     报告文件；null 时若项目里恰好只有一份带证据关联的文档就用它，
     *                      0 份或多份则跳过挂链并在 unlinked 里说明，不猜也不报错（落盘照常）
     * @param createdByKind 新建 target 的来源：REST 传 human，AI 工具传 ai
     */
    @Transactional
    public ImportResult importArchive(Long userId, Long projectId, String partyName, String unifiedSocialCreditCode,
                                      List<String> siteCodes, Long docFileId, byte[] zipBytes, String createdByKind) {
        if (userId == null || projectId == null || !projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project"));
        }
        String party = sanitizeName(partyName);
        if (!StringUtils.hasText(party)) {
            throw new IllegalArgumentException(LangText.of("主体名不能为空", "Party name must not be empty"));
        }
        // 归属校验早于任何落盘：跨项目的 docFileId 要直接拒，不能先写一堆文件再报错
        if (docFileId != null) requireProjectFile(projectId, docFileId);

        List<WebVerifySite> sites = parseSites(siteCodes);
        WebVerifyProvider provider = provider();
        List<WebVerifyResult> results = provider.verify(
                new WebVerifyRequest(partyName, unifiedSocialCreditCode, sites, zipBytes));
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException(sites.isEmpty()
                    ? LangText.of("压缩包里没有可导入的网核件", "The archive contains no web-verify material")
                    : LangText.of("压缩包里没有属于所选站点的网核件", "The archive contains no material for the selected sites"));
        }

        // 定位报告：显式给了就用；没给就看项目里是不是只有一份带证据关联的文档
        Long resolvedDocFileId = docFileId;
        String skipReason = null;
        if (resolvedDocFileId == null) {
            List<Long> candidates = evidenceLinkRepository.findDistinctDocFileIdsByProjectId(projectId);
            if (candidates.size() == 1) {
                resolvedDocFileId = candidates.get(0);
            } else {
                skipReason = candidates.isEmpty()
                        ? LangText.of("项目里还没有任何证据关联，无法确定报告文件（可指定 docFileId 后重试）",
                                      "No evidence links in this project yet; pass docFileId to link")
                        : LangText.of("项目里有多份带证据关联的文档，请指定 docFileId 后重试",
                                      "Multiple documents carry evidence links; pass docFileId to link");
            }
        }

        List<LinkView> candidateLinks = resolvedDocFileId == null ? List.of()
                : webCheckLinksOfParty(userId, projectId, resolvedDocFileId, partyName);
        if (skipReason == null && candidateLinks.isEmpty()) {
            skipReason = LangText.of("报告里没有提到该主体的网络核查段落", "No web-check paragraph mentions this party");
        }

        ProjectFile folder = projectFileService.ensureFolderPath(projectId, userId, List.of(WEB_VERIFY_FOLDER, party));
        StorageService storage = storageServiceFactory.getStorageService();

        List<LandedItem> items = new ArrayList<>();
        List<UnlinkedItem> unlinked = new ArrayList<>();
        for (WebVerifyResult r : results) {
            ProjectFile saved = land(projectId, userId, folder, storage, r, provider.providerId());
            String path = WEB_VERIFY_FOLDER + "/" + party + "/" + saved.getName();

            List<String> linkedKeys = new ArrayList<>();
            String reason = skipReason;
            if (reason == null) {
                for (LinkView l : narrowToSite(candidateLinks, r.site())) {
                    try {
                        evidenceLinkService.addTargets(userId, projectId, l.linkKey(),
                                List.of(targetOf(saved.getId(), r)), createdByKind);
                        linkedKeys.add(l.linkKey());
                    } catch (RuntimeException e) {
                        // 单条挂链失败（链接同期被删、同位置已挂等）不该把整批落盘回滚掉，如实报出来
                        log.warn("网核件挂链失败 project={} link={} file={}", projectId, l.linkKey(), saved.getId(), e);
                        reason = e.getMessage();
                    }
                }
                if (linkedKeys.isEmpty() && reason == null) {
                    reason = LangText.of("报告里没有提到该主体的网络核查段落", "No web-check paragraph mentions this party");
                }
            }

            items.add(new LandedItem(saved.getId(), path, r.site().code(), r.site().label(),
                    r.queriedAt() == null ? null : r.queriedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    r.sourceUrl(), r.summary(), linkedKeys));
            if (linkedKeys.isEmpty()) {
                unlinked.add(new UnlinkedItem(saved.getId(), path, r.site().code(), reason));
            }
        }
        log.info("网核导入完成 project={} party={} 落盘 {} 件、未挂链 {} 件", projectId, party, items.size(), unlinked.size());
        return new ImportResult(partyName, provider.providerId(), resolvedDocFileId, items.size(), items, unlinked);
    }

    // ------------------------------------------------------------------ 落盘

    private ProjectFile land(Long projectId, Long userId, ProjectFile folder, StorageService storage,
                             WebVerifyResult r, String providerId) {
        if (r.bytes() == null) {
            // 只给 sourcePath 的适配器本仓还没有（离线适配器一律给字节）。真出现时要报错，
            // 不能顺手落一个 0 字节的「截图」——那等于把没有内容的证据写进底稿。
            throw new IllegalStateException(LangText.of("网核适配器没有给出文件内容: ",
                    "The web-verify provider returned no content for: ") + r.fileName());
        }
        byte[] bytes = r.bytes();
        ProjectFile file = projectFileService.createFile(projectId, folder.getId(), r.fileName(), r.ext(),
                (long) bytes.length, null, null, userId, ProjectFileService.ConflictPolicy.RENAME);
        try {
            storage.save(file.getFilePath(), new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("保存网核件失败: ", "Failed to save web-verify material: ")
                    + e.getMessage(), e);
        }
        ObjectNode meta = objectMapper.createObjectNode();
        if (StringUtils.hasText(r.sourceUrl())) meta.put("sourceUrl", r.sourceUrl());
        if (r.queriedAt() != null) meta.put("capturedAt", r.queriedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.put("provider", providerId);
        meta.put("site", r.site().code());
        file.setMetaJson(meta.toString());
        return projectFileRepository.save(file);
    }

    // ------------------------------------------------------------------ 挂链

    /** 候选段落：既有 method=web_check 的 target，又在锚点文字/章节里提到该主体。两条缺一不可。 */
    private List<LinkView> webCheckLinksOfParty(Long userId, Long projectId, Long docFileId, String partyName) {
        List<LinkView> out = new ArrayList<>();
        for (LinkView l : evidenceLinkService.listByDoc(userId, projectId, docFileId, null, null)) {
            if (!mentions(l, partyName)) continue;
            for (TargetView t : l.targets()) {
                if (METHOD_WEB_CHECK.equals(t.method())) { out.add(l); break; }
            }
        }
        return out;
    }

    /** 候选里若有段落点名了这个站点，就只挂那些；否则整批候选都挂。 */
    static List<LinkView> narrowToSite(List<LinkView> candidates, WebVerifySite site) {
        List<String> needles = new ArrayList<>();
        needles.add(site.label());
        needles.addAll(site.aliases());
        List<String> nonEmpty = needles.stream().filter(StringUtils::hasText).toList();
        List<LinkView> hit = new ArrayList<>();
        for (LinkView l : candidates) {
            String text = textOf(l);
            for (String n : nonEmpty) {
                if (text.contains(n)) { hit.add(l); break; }
            }
        }
        return hit.isEmpty() ? candidates : hit;
    }

    private static boolean mentions(LinkView l, String partyName) {
        return StringUtils.hasText(partyName) && textOf(l).contains(partyName.trim());
    }

    private static String textOf(LinkView l) {
        return (l.anchorText() == null ? "" : l.anchorText())
                + " " + (l.sectionPath() == null ? "" : l.sectionPath())
                + " " + (l.sectionTitle() == null ? "" : l.sectionTitle());
    }

    /** web 型 locator（spec §1.4）：{@code {"type":"web","url":…,"capturedAt":…}}。 */
    private TargetInput targetOf(Long fileId, WebVerifyResult r) {
        ObjectNode loc = objectMapper.createObjectNode();
        loc.put("type", "web");
        if (StringUtils.hasText(r.sourceUrl())) loc.put("url", r.sourceUrl());
        if (r.queriedAt() != null) loc.put("capturedAt", r.queriedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        String note = r.summary();
        if (note != null && note.length() > NOTE_MAX) note = note.substring(0, NOTE_MAX);
        return new TargetInput(fileId, loc.toString(), "supports", METHOD_WEB_CHECK, null, note);
    }

    // ------------------------------------------------------------------ 入参

    /** 站点 code 严格解析：认不出直接报错并列出可用取值，不能悄悄退成 OTHER 把筛选变成全收。 */
    static List<WebVerifySite> parseSites(List<String> codes) {
        List<WebVerifySite> out = new ArrayList<>();
        if (codes == null) return out;
        for (String c : codes) {
            if (!StringUtils.hasText(c)) continue;
            WebVerifySite s = null;
            for (WebVerifySite v : WebVerifySite.values()) {
                if (v.code().equalsIgnoreCase(c.trim()) || v.name().equalsIgnoreCase(c.trim())) { s = v; break; }
            }
            if (s == null) {
                throw new IllegalArgumentException(LangText.of("站点取值非法: ", "Illegal site: ") + c
                        + LangText.of("；可用取值: ", "; allowed: ")
                        + String.join(", ", java.util.Arrays.stream(WebVerifySite.values()).map(WebVerifySite::code).toList()));
            }
            out.add(s);
        }
        return out;
    }

    /** 主体名清洗：去路径分隔符与 Windows 保留字符、去首尾点（".."）、截长度。 */
    static String sanitizeName(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll(ILLEGAL_NAME_CHARS, "").trim();
        s = s.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "").trim();
        if (s.length() > PARTY_NAME_MAX) s = s.substring(0, PARTY_NAME_MAX);
        return s;
    }

    private WebVerifyProvider provider() {
        for (WebVerifyProvider p : providers) {
            if (ManualWebVerifyProvider.ID.equals(p.providerId())) return p;
        }
        if (providers.size() == 1) return providers.get(0);
        throw new IllegalStateException(LangText.of("没有可用的网核适配器", "No web-verify provider available"));
    }

    private ProjectFile requireProjectFile(Long projectId, Long fileId) {
        ProjectFile f = fileId == null ? null : projectFileRepository.findById(fileId).orElse(null);
        if (f == null || !projectId.equals(f.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于该项目: ", "File not in project: ") + fileId);
        }
        return f;
    }
}
