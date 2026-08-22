package com.checkba.service;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.DocxStyleHelper;
import com.checkba.util.style.StyleProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.docx.converter.DocxRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 尽调交付件导出（dev-board#100 P2）：底稿目录 / 查验计划 / 缺口清单，docx + xlsx 两种格式。
 *
 * <p>数据源只有既有的 {@link EvidenceLinkService}（EvidenceLink 两张表）与
 * {@link ProjectFileRepository}（project_file，含 metaJson.docketNo）——不依赖尽调插件：
 * 那是私有仓 aiworkdeck-dd-plugin 的闭源 JAR，主仓看不见也编译不了它。EvidenceLink 本身是
 * 平台内置、不门控的底层能力（spec 2026-08-21 拍板 §4 第 4 条），三份导出照此内置在主仓。
 *
 * <p>与 {@code controller/DdController.java}（DdRequest/DdItem/DdComment）无关——那是旧的
 * 面向客户协作的「尽调清单」插件，本类是新的 EvidenceLink 驱动的交付件导出，两者除了都译作
 * 「尽调/DD」外没有任何代码关联。
 *
 * <p>落盘约定：生成的文件写进项目 {@code _交付件/} 文件夹，同名就地覆盖（不生成「(1)」副本），
 * 写法与 {@code TemplateTools.saveProjectProfile} 同一模式。docx 套用
 * {@link StyleProfileResolver#resolve} 解出的项目画像；xlsx 用 POI 直接写（与
 * {@code DocumentEditTools.sheet_create_file} 同一条路径，未引入新依赖）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DdExportService {

    public static final String KIND_DOCKET = "docket";
    public static final String KIND_VERIFY_PLAN = "verify-plan";
    public static final String KIND_GAPS = "gaps";
    private static final Set<String> KINDS = Set.of(KIND_DOCKET, KIND_VERIFY_PLAN, KIND_GAPS);

    public static final String FORMAT_DOCX = "docx";
    public static final String FORMAT_XLSX = "xlsx";

    /** 交付件权威存放目录（spec §4「缺口清单与底稿目录：一键导出 xlsx / docx」）。 */
    public static final String DELIVERABLE_FOLDER = "_交付件";

    private static final int TRUNCATE_LEN = 80;
    /** 正文占位符：【待补：xxx】，容忍模型偶尔写成半角冒号。 */
    private static final Pattern GAP_PLACEHOLDER = Pattern.compile("【待补[:：]([^】]*)】");
    /** EvidenceLinkTarget.METHODS 的展示顺序；""（未注明）固定排最后。 */
    private static final List<String> METHOD_ORDER =
            List.of("written_review", "written_statement", "web_check", "third_party", "interview", "");
    private static final Map<String, String> METHOD_LABELS = Map.of(
            "written_review", "书面审查",
            "written_statement", "书面说明",
            "web_check", "网络核查",
            "third_party", "第三方材料",
            "interview", "访谈",
            "", "未注明查验方式");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            EvidenceLink.STATUS_ACTIVE, "已确认",
            EvidenceLink.STATUS_UNVERIFIED, "待核实",
            EvidenceLink.STATUS_STALE, "锚点已变",
            EvidenceLink.STATUS_ORPHAN, "已失效");

    private final EvidenceLinkService evidenceLinkService;
    private final EvidenceLinkRepository evidenceLinkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final DocumentTextService documentTextService;
    private final StyleProfileResolver styleProfileResolver;
    private final ProjectMemberService projectMemberService;
    private final ObjectMapper objectMapper;

    public record ExportResult(Long fileId, String path, int rows) {}

    /** 底稿目录一行 = 一个（被引文件, 反向链接）组合。按 chapter 一级、file 二级、link 三级的先出现顺序排列。 */
    public record DocketRow(String chapter, Long fileId, String fileName, String fileType, String docketNo,
                            String sectionPath, String sectionTitle, String anchorText, String method, String status) {}

    /** 查验计划一行 = 一个（method, 被引文件, 引用段落）组合，按 method 固定枚举顺序排列。 */
    public record VerifyPlanRow(String method, Long fileId, String fileName, String fileType,
                                String sectionPath, String sectionTitle, String anchorText) {}

    /** 缺口清单一行：type ∈ {"占位符","孤儿关联"}。占位符没有可靠的章节定位，note 放上下文片段。 */
    public record GapRow(String type, String content, String location, String note) {}

    // ==================================================================== 入口

    @Transactional
    public ExportResult export(Long userId, Long projectId, Long docFileId, String kind, String format) {
        if (kind == null || !KINDS.contains(kind.trim())) {
            throw new IllegalArgumentException(LangText.of(
                    "kind 只能是 docket/verify-plan/gaps: ", "kind must be docket/verify-plan/gaps: ") + kind);
        }
        String k = kind.trim();
        String fmt = StringUtils.hasText(format) ? format.trim().toLowerCase() : FORMAT_DOCX;
        if (!FORMAT_DOCX.equals(fmt) && !FORMAT_XLSX.equals(fmt)) {
            throw new IllegalArgumentException(LangText.of(
                    "format 只能是 docx 或 xlsx: ", "format must be docx or xlsx: ") + format);
        }
        if (userId == null || projectId == null || !projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project"));
        }
        ProjectFile reportFile = requireProjectFile(projectId, docFileId);

        String baseName;
        int rowCount;
        byte[] bytes;
        try {
            switch (k) {
                case KIND_DOCKET -> {
                    List<LinkView> links = evidenceLinkService.listByDoc(userId, projectId, docFileId, null, null);
                    List<DocketRow> rows = enrichDocketNos(groupDocket(links));
                    rowCount = rows.size();
                    baseName = "底稿目录";
                    bytes = FORMAT_DOCX.equals(fmt)
                            ? renderDocx(projectId, baseName, docketMarkdown(rows))
                            : renderDocketXlsx(rows);
                }
                case KIND_VERIFY_PLAN -> {
                    List<LinkView> links = evidenceLinkService.listByDoc(userId, projectId, docFileId, null, null);
                    List<VerifyPlanRow> rows = groupVerifyPlan(links);
                    rowCount = rows.size();
                    baseName = "查验计划";
                    bytes = FORMAT_DOCX.equals(fmt)
                            ? renderDocx(projectId, baseName, verifyPlanMarkdown(rows))
                            : renderVerifyPlanXlsx(rows);
                }
                case KIND_GAPS -> {
                    String bodyText = extractReportText(reportFile);
                    List<EvidenceLink> orphans =
                            evidenceLinkRepository.findByProjectIdAndStatusOrderByIdAsc(projectId, EvidenceLink.STATUS_ORPHAN);
                    List<GapRow> rows = buildGaps(bodyText, orphans);
                    rowCount = rows.size();
                    baseName = "缺口清单";
                    bytes = FORMAT_DOCX.equals(fmt)
                            ? renderDocx(projectId, baseName, gapsMarkdown(rows))
                            : renderGapsXlsx(rows);
                }
                default -> throw new IllegalStateException("unreachable kind: " + k);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("dd-export 生成失败 kind={} format={} projectId={} docFileId={}", k, fmt, projectId, docFileId, e);
            throw new IllegalStateException(LangText.of("生成交付件失败: ", "Failed to generate deliverable: ") + e.getMessage(), e);
        }

        String fileName = baseName + "." + fmt;
        ProjectFile saved = saveIntoDeliverables(projectId, userId, fileName, fmt, bytes);
        return new ExportResult(saved.getId(), DELIVERABLE_FOLDER + "/" + fileName, rowCount);
    }

    // ==================================================================== 分组（纯函数，便于单测）

    static String chapterOf(String sectionPath) {
        if (!StringUtils.hasText(sectionPath)) return "未分类";
        String s = sectionPath.trim();
        int i = s.indexOf('/');
        String head = i < 0 ? s : s.substring(0, i);
        return StringUtils.hasText(head) ? head.trim() : "未分类";
    }

    /** 底稿目录分组：先按 link 出现顺序展开每个 target 为一行；docketNo 留空，由调用方批量回填。 */
    static List<DocketRow> groupDocket(List<LinkView> links) {
        List<DocketRow> out = new ArrayList<>();
        if (links == null) return out;
        for (LinkView l : links) {
            String chapter = chapterOf(l.sectionPath());
            for (TargetView t : l.targets()) {
                out.add(new DocketRow(chapter, t.fileId(), fileName(t), fileType(t), "",
                        l.sectionPath(), l.sectionTitle(), truncate(l.anchorText()), t.method(), l.status()));
            }
        }
        return out;
    }

    /** 查验计划分组：按 EvidenceLinkTarget.METHODS 固定顺序归类，method 为空/null 归入「未注明」桶排最后。 */
    static List<VerifyPlanRow> groupVerifyPlan(List<LinkView> links) {
        Map<String, List<VerifyPlanRow>> byMethod = new LinkedHashMap<>();
        if (links != null) {
            for (LinkView l : links) {
                for (TargetView t : l.targets()) {
                    String m = t.method() == null ? "" : t.method();
                    byMethod.computeIfAbsent(m, k -> new ArrayList<>()).add(new VerifyPlanRow(m,
                            t.fileId(), fileName(t), fileType(t), l.sectionPath(), l.sectionTitle(), truncate(l.anchorText())));
                }
            }
        }
        List<VerifyPlanRow> out = new ArrayList<>();
        for (String m : METHOD_ORDER) out.addAll(byMethod.getOrDefault(m, List.of()));
        return out;
    }

    /** 缺口清单：正文占位符（按出现顺序）在前，orphan link（按 id 顺序）在后。 */
    static List<GapRow> buildGaps(String reportBodyText, List<EvidenceLink> orphanLinks) {
        List<GapRow> out = new ArrayList<>();
        if (StringUtils.hasText(reportBodyText)) {
            Matcher m = GAP_PLACEHOLDER.matcher(reportBodyText);
            while (m.find()) {
                out.add(new GapRow("占位符", truncate(m.group()), "", contextAround(reportBodyText, m.start(), m.end())));
            }
        }
        if (orphanLinks != null) {
            for (EvidenceLink l : orphanLinks) {
                String loc = (StringUtils.hasText(l.getSectionPath()) ? l.getSectionPath() : "")
                        + (StringUtils.hasText(l.getSectionTitle()) ? " " + l.getSectionTitle() : "");
                out.add(new GapRow("孤儿关联", truncate(l.getAnchorText()), loc.trim(), l.getLinkKey()));
            }
        }
        return out;
    }

    private static String contextAround(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        return truncate(text.substring(from, to).replaceAll("\\s+", " "));
    }

    private static String fileName(TargetView t) {
        return t.file() != null && StringUtils.hasText(t.file().name()) ? t.file().name() : ("文件#" + t.fileId());
    }

    private static String fileType(TargetView t) {
        return t.file() != null && t.file().fileType() != null ? t.file().fileType() : "";
    }

    static String truncate(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > TRUNCATE_LEN ? t.substring(0, TRUNCATE_LEN) : t;
    }

    private static String methodLabel(String m) {
        String key = m == null ? "" : m;
        return METHOD_LABELS.getOrDefault(key, key);
    }

    private static String statusLabel(String s) {
        return STATUS_LABELS.getOrDefault(s, s == null ? "" : s);
    }

    private static String sectionOf(String path, String title) {
        String p = path == null ? "" : path;
        String t = StringUtils.hasText(title) ? " " + title : "";
        return (p + t).trim();
    }

    /** metaJson.docketNo 批量回填（P1 dd_ingest 写入；没跑过入库整理的项目里为空，导出照常，只是这一列空白）。 */
    private List<DocketRow> enrichDocketNos(List<DocketRow> rows) {
        if (rows.isEmpty()) return rows;
        Set<Long> fileIds = new LinkedHashSet<>();
        for (DocketRow r : rows) fileIds.add(r.fileId());
        Map<Long, String> byId = new LinkedHashMap<>();
        for (ProjectFile f : projectFileRepository.findAllById(fileIds)) byId.put(f.getId(), docketNoOf(f));
        List<DocketRow> out = new ArrayList<>(rows.size());
        for (DocketRow r : rows) {
            out.add(new DocketRow(r.chapter(), r.fileId(), r.fileName(), r.fileType(), byId.getOrDefault(r.fileId(), ""),
                    r.sectionPath(), r.sectionTitle(), r.anchorText(), r.method(), r.status()));
        }
        return out;
    }

    private String docketNoOf(ProjectFile f) {
        if (f == null || !StringUtils.hasText(f.getMetaJson())) return "";
        try {
            JsonNode n = objectMapper.readTree(f.getMetaJson());
            if (n != null && n.hasNonNull("docketNo")) {
                JsonNode v = n.get("docketNo");
                return v.isTextual() ? v.asText() : v.toString();
            }
        } catch (Exception e) {
            log.warn("dd-export: 解析 metaJson.docketNo 失败 fileId={}", f.getId(), e);
        }
        return "";
    }

    // ==================================================================== markdown 渲染（docx）

    private static String cell(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    private String docketMarkdown(List<DocketRow> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String cur = null;
        for (DocketRow r : rows) {
            if (!Objects.equals(cur, r.chapter())) {
                cur = r.chapter();
                sb.append("\n## ").append(cell(cur)).append("\n\n")
                  .append("| 底稿文件 | 编号 | 类型 | 引用段落 | 引用文字 | 查验方式 | 状态 |\n")
                  .append("|---|---|---|---|---|---|---|\n");
            }
            sb.append("| ").append(cell(r.fileName())).append(" | ").append(cell(r.docketNo())).append(" | ")
              .append(cell(r.fileType())).append(" | ").append(cell(sectionOf(r.sectionPath(), r.sectionTitle())))
              .append(" | ").append(cell(r.anchorText())).append(" | ").append(cell(methodLabel(r.method())))
              .append(" | ").append(cell(statusLabel(r.status()))).append(" |\n");
        }
        return sb.toString();
    }

    private String verifyPlanMarkdown(List<VerifyPlanRow> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String cur = null;
        for (VerifyPlanRow r : rows) {
            if (!Objects.equals(cur, r.method())) {
                cur = r.method();
                sb.append("\n## ").append(cell(methodLabel(cur))).append("\n\n")
                  .append("| 底稿文件 | 类型 | 引用段落 | 引用文字 |\n")
                  .append("|---|---|---|---|\n");
            }
            sb.append("| ").append(cell(r.fileName())).append(" | ").append(cell(r.fileType())).append(" | ")
              .append(cell(sectionOf(r.sectionPath(), r.sectionTitle()))).append(" | ").append(cell(r.anchorText())).append(" |\n");
        }
        return sb.toString();
    }

    private String gapsMarkdown(List<GapRow> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        List<GapRow> placeholders = rows.stream().filter(r -> "占位符".equals(r.type())).toList();
        List<GapRow> orphans = rows.stream().filter(r -> "孤儿关联".equals(r.type())).toList();
        if (!placeholders.isEmpty()) {
            sb.append("\n## 正文占位符\n\n| 内容 | 上下文 |\n|---|---|\n");
            for (GapRow r : placeholders) {
                sb.append("| ").append(cell(r.content())).append(" | ").append(cell(r.note())).append(" |\n");
            }
        }
        if (!orphans.isEmpty()) {
            sb.append("\n## 孤儿关联的证据链接\n\n| 引用文字 | 位置 | linkKey |\n|---|---|---|\n");
            for (GapRow r : orphans) {
                sb.append("| ").append(cell(r.content())).append(" | ").append(cell(r.location())).append(" | ")
                  .append(cell(r.note())).append(" |\n");
            }
        }
        return sb.toString();
    }

    private byte[] renderDocx(Long projectId, String title, String markdownBody) throws Exception {
        String md = "# " + title + "\n\n" + (StringUtils.hasText(markdownBody) ? markdownBody : "（无数据）\n");
        MutableDataSet options = AiDocxExportService.markdownOptions();
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        DocxStyleHelper.addMissingStyles(pkg);
        DocxRenderer.builder(options).build().render(Parser.builder(options).build().parse(md), pkg);
        StyleProfile profile = styleProfileResolver.resolve(projectId, null);
        DocxStyleHelper.applyProfile(pkg, profile);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pkg.save(bos);
        return bos.toByteArray();
    }

    // ==================================================================== xlsx 渲染（POI，同 sheet_create_file 一条路径）

    private byte[] xlsxOf(String[] headers, List<String[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            int rowIdx = 1;
            for (String[] r : rows) {
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < r.length; i++) {
                    row.createCell(i).setCellValue(r[i] == null ? "" : r[i]);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] renderDocketXlsx(List<DocketRow> rows) throws Exception {
        String[] headers = {"章节", "底稿文件", "编号", "类型", "引用段落路径", "引用段落标题", "引用文字", "查验方式", "状态"};
        List<String[]> data = new ArrayList<>();
        for (DocketRow r : rows) {
            data.add(new String[]{r.chapter(), r.fileName(), r.docketNo(), r.fileType(), nz(r.sectionPath()),
                    nz(r.sectionTitle()), r.anchorText(), methodLabel(r.method()), statusLabel(r.status())});
        }
        return xlsxOf(headers, data);
    }

    private byte[] renderVerifyPlanXlsx(List<VerifyPlanRow> rows) throws Exception {
        String[] headers = {"查验方式", "底稿文件", "类型", "引用段落路径", "引用段落标题", "引用文字"};
        List<String[]> data = new ArrayList<>();
        for (VerifyPlanRow r : rows) {
            data.add(new String[]{methodLabel(r.method()), r.fileName(), r.fileType(), nz(r.sectionPath()),
                    nz(r.sectionTitle()), r.anchorText()});
        }
        return xlsxOf(headers, data);
    }

    private byte[] renderGapsXlsx(List<GapRow> rows) throws Exception {
        String[] headers = {"类型", "内容", "位置", "备注"};
        List<String[]> data = new ArrayList<>();
        for (GapRow r : rows) {
            data.add(new String[]{r.type(), r.content(), r.location(), r.note()});
        }
        return xlsxOf(headers, data);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ==================================================================== 归属校验、正文抽取、落盘

    private ProjectFile requireProjectFile(Long projectId, Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 必填", "docFileId required"));
        }
        ProjectFile f = projectFileRepository.findById(fileId).orElse(null);
        if (f == null || !projectId.equals(f.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于该项目: ", "File not in project: ") + fileId);
        }
        return f;
    }

    /** 用既有的 DocumentTextService（docx→Tika）取正文，抽取失败不让整个导出失败，只是缺口清单少了占位符那一半。 */
    private String extractReportText(ProjectFile f) {
        try {
            return documentTextService.extractText(f);
        } catch (Exception e) {
            log.warn("dd-export: 抽取报告正文失败 fileId={}", f.getId(), e);
            return "";
        }
    }

    /** 同名就地覆盖（不生成「(1)」副本），与 TemplateTools.saveProjectProfile 同一模式。 */
    private ProjectFile saveIntoDeliverables(Long projectId, Long userId, String fileName, String fileType, byte[] bytes) {
        ProjectFile folder = projectFileService.ensureFolderPath(projectId, userId, List.of(DELIVERABLE_FOLDER));
        Optional<ProjectFile> existing =
                projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, folder.getId(), fileName);
        StorageService storage = storageServiceFactory.getStorageService();
        ProjectFile target = existing.orElseGet(() -> projectFileService.createFile(
                projectId, folder.getId(), fileName, fileType, (long) bytes.length, null, null, userId));
        try {
            storage.save(target.getFilePath(), new ByteArrayInputStream(bytes));
        } catch (StorageException e) {
            throw new IllegalStateException(LangText.of("保存交付件失败: ", "Failed to save deliverable: ") + e.getMessage(), e);
        }
        if (existing.isPresent()) {
            projectFileService.createOrUpdateFile(projectId, folder.getId(), fileName, fileType,
                    (long) bytes.length, target.getFilePath(), null, userId);
        }
        return target;
    }
}
