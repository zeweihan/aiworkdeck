package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.evidence.webverify.WebVerifyImportService;
import com.checkba.service.evidence.webverify.WebVerifySite;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 网核 zip 导入（dev-board#100 P3）的 AI 工具入口。核心逻辑全在 {@link WebVerifyImportService}，
 * 这里只做「zip 已经在项目里」时的取字节与 fileId 缺省定位，以及面向模型的摘要文案。
 *
 * <p><b>本工具不联网</b>：它只能消费用户已经放进项目里的、由外部工具导出的 zip
 * （2026-08-21 拍板：网核只留接口，不做自动爬取）。模型不要指望用它去「查一下某公司」。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebVerifyTools implements AgentToolComponent {

    private final WebVerifyImportService webVerifyImportService;
    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;

    @ToolMeta(displayName = "导入网核压缩包", category = "file", fileEffect = "ADDED", refreshFiles = true)
    @Tool("把项目里已有的网核压缩包（外部工具导出的 zip，含各站点截图与页面文本）解包落进 _网核/<主体>/ 并自动挂到报告里" +
          "对应的网络核查段落上。站点取值：credit_publicity 企业信用信息公示、judgment_docs 裁判文书、" +
          "dishonest_executee 失信被执行人、executee 被执行人、env_penalty 环保处罚、admin_penalty 行政处罚、" +
          "intellectual_property 知识产权、other 其他。注意：本工具不联网，不能替你去任何网站查询，" +
          "只能导入用户已经放进项目里的 zip；报告里找不到「提到该主体且查验方式为网络核查」的段落时只落盘不挂链，" +
          "并把未挂链的件列出来。")
    public String web_verify_import(
            @P("主体名（公司/自然人全称），落盘目录与挂链匹配都按它") String partyName,
            @P(value = "项目内网核 zip 的文件 ID；缺省时若项目里恰好只有一个 zip 会自动用它，否则报错并列出候选", required = false) Long fileId,
            @P(value = "统一社会信用代码，可空", required = false) String unifiedSocialCreditCode,
            @P(value = "只导入这些站点，逗号分隔；缺省 = 包里有什么导什么", required = false) String sites,
            @P(value = "报告所在的项目文件 ID；缺省时若项目里只有一份带底稿关联的文档会自动使用它", required = false) Long docFileId,
            Long projectId,
            Long userId
    ) {
        log.info("Tool: web_verify_import called party='{}', fileId={}, sites={}", partyName, fileId, sites);
        try {
            Long zipFileId = fileId != null ? fileId : resolveSoleZip(projectId);
            byte[] bytes = readZip(projectId, zipFileId);
            WebVerifyImportService.ImportResult r = webVerifyImportService.importArchive(userId, projectId, partyName,
                    unifiedSocialCreditCode, splitSites(sites), docFileId, bytes, "ai");
            return summarize(r);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("web_verify_import failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /** 面向模型的摘要：挂链结果与未挂链清单都要说清楚，别让模型以为「导入成功 = 都挂上了」。 */
    private static String summarize(WebVerifyImportService.ImportResult r) {
        StringBuilder sb = new StringBuilder();
        int linked = (int) r.items().stream().filter(i -> !i.linkedKeys().isEmpty()).count();
        sb.append(String.format("已导入 %d 件网核材料（主体：%s，适配器：%s），其中 %d 件已挂到报告段落。",
                r.landed(), r.partyName(), r.provider(), linked));
        for (WebVerifyImportService.LandedItem i : r.items()) {
            sb.append("\n- ").append(i.path()).append("（").append(i.siteLabel()).append("，文件ID: ").append(i.fileId()).append("）");
            if (!i.linkedKeys().isEmpty()) sb.append(" → ").append(String.join("、", i.linkedKeys()));
        }
        if (!r.unlinked().isEmpty()) {
            sb.append("\n未挂链 ").append(r.unlinked().size()).append(" 件（只落了盘，需要人工确认挂到哪一段）：");
            for (WebVerifyImportService.UnlinkedItem u : r.unlinked()) {
                sb.append("\n- ").append(u.path()).append("：").append(u.reason());
            }
        }
        return sb.toString();
    }

    /** 项目里恰好只有一个 zip 时才自动定位，否则要求模型显式指定（与 dd_export 的口径一致，不猜）。 */
    private Long resolveSoleZip(Long projectId) {
        List<ProjectFile> zips = new ArrayList<>();
        for (ProjectFile f : projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId)) {
            if (!Boolean.TRUE.equals(f.getIsFolder()) && "zip".equalsIgnoreCase(f.getFileType())) zips.add(f);
        }
        if (zips.isEmpty()) {
            throw new IllegalArgumentException("项目里没有 zip 文件。网核材料需要用户先把外部工具导出的 zip 放进项目——本工具不联网抓取。");
        }
        if (zips.size() > 1) {
            StringBuilder sb = new StringBuilder("项目里有多个 zip，请显式指定 fileId，候选：");
            for (ProjectFile f : zips) sb.append("\n- ").append(f.getName()).append("（文件ID: ").append(f.getId()).append("）");
            throw new IllegalArgumentException(sb.toString());
        }
        return zips.get(0).getId();
    }

    private byte[] readZip(Long projectId, Long fileId) {
        ProjectFile f = projectFileRepository.findById(fileId).orElse(null);
        if (f == null || !projectId.equals(f.getProjectId()) || Boolean.TRUE.equals(f.getIsDeleted())) {
            throw new IllegalArgumentException("文件不属于该项目: " + fileId);
        }
        if (Boolean.TRUE.equals(f.getIsFolder())) {
            throw new IllegalArgumentException("这是文件夹不是压缩包: " + f.getName());
        }
        try {
            byte[] bytes = projectFileService.getFileBytes(fileId);
            if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("压缩包为空或读不到: " + f.getName());
            return bytes;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取压缩包失败: " + e.getMessage(), e);
        }
    }

    static List<String> splitSites(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,，]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 工具描述里的站点清单与枚举同源的锚点（枚举加了站点忘了改描述时，WebVerifyToolsTest 会红）。 */
    static List<String> siteCodes() {
        return Arrays.stream(WebVerifySite.values()).map(WebVerifySite::code).toList();
    }
}
