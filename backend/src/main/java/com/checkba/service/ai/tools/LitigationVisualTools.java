package com.checkba.service.ai.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.LitigationVisualService;
import com.checkba.storage.ProjectStorageResolver;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诉讼可视化工具：把案件事实画成时间轴 / 流程图 / 当事人关系图。
 *
 * <p>出图引擎 vendor 自 mqc-litigation-visual-redraw（作者缪奇川，MIT），
 * 见仓库 {@code litviz/}。引擎有一条刻意的分工，这里完整继承：
 * <b>模型只做抽取，脚本负责画。</b>模型产出一份 semantic-map JSON，全部几何
 * （列宽、日期比例、折行、防重叠、连线路由）由确定性脚本计算。
 * 所以工具描述里一再强调「不要手写 SVG 坐标」——那是这套设计的地基。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LitigationVisualTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LitigationVisualTools.class);

    private static final Long AGENT_USER_ID = 10001L;

    /** 默认交付全部五种格式：SVG 母版 + PNG 预览 + 三种律师能接着改的源文件。 */
    private static final String DEFAULT_FORMATS = "svg,png,pptx,drawio,vsdx";

    /**
     * 产物与语义地图的身份标记，写在 wpsFileId 前缀里。
     *
     * <p>为什么用 wpsFileId 当标记而不是新加一列：ProjectFile 是全项目共用的表，
     * 为一个功能加列的代价远大于收益，而 wpsFileId 本来就是"这个文件是谁造的"的
     * 自由字段（write_docx 写 _ai_、PDF 转换写 _ai_）。诉讼可视化面板靠这个前缀
     * 把自己生成的图从整棵文件树里认出来。
     */
    public static final String MARKER_ARTIFACT = "project_litviz_";
    public static final String MARKER_MAP = "project_litvizmap_";

    /** 参考文档的白名单别名。LLM 只能报这些名字，不能自己拼路径。 */
    private static final Map<String, String> REFERENCES = new LinkedHashMap<>();
    static {
        REFERENCES.put("standards", "references/STANDARDS.md");
        REFERENCES.put("extraction", "references/extraction-guide.md");
        REFERENCES.put("schema", "references/semantic-map-schema.md");
        REFERENCES.put("visual-style", "references/visual-style.md");
        REFERENCES.put("fidelity", "references/fidelity-rules.md");
        REFERENCES.put("flowchart", "references/flowchart-spec.md");
        REFERENCES.put("relationship", "references/relationship-spec.md");
        REFERENCES.put("example-timeline-points", "examples/timeline-points.json");
        REFERENCES.put("example-timeline-dated", "examples/timeline-dated.json");
        REFERENCES.put("example-timeline-gantt", "examples/timeline-gantt.json");
        REFERENCES.put("example-flowchart", "examples/flowchart.json");
        REFERENCES.put("example-relationship", "examples/relationship.json");
        REFERENCES.put("example-relation-tree", "examples/relation-tree.json");
        REFERENCES.put("example-comparison-table", "examples/comparison-table.json");
    }

    private final LitigationVisualService litviz;
    private final com.checkba.service.ai.LitigationPngService pngService;
    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectStorageResolver storageResolver;
    private final EditorBridgeService editorBridgeService;

    // ==================== 参考文档（渐进披露） ====================

    @ToolMeta(displayName = "查阅制图规范", category = "litigation-visual")
    @Tool("Read one reference document of the litigation-diagram standard. Call this BEFORE writing a "
            + "semantic map when you are unsure about fields, shapes or the extraction discipline — the "
            + "docs are large and are NOT preloaded. Names: standards (authoritative, read first), "
            + "extraction (how to read/decompose an ugly source), schema (every JSON field), visual-style, "
            + "fidelity, flowchart, relationship, and worked examples: example-timeline-points, "
            + "example-timeline-dated, example-timeline-gantt, example-flowchart, example-relationship, "
            + "example-relation-tree, example-comparison-table.")
    public String litigation_reference(@P("Document name from the list above") String name) {
        String key = name == null ? "" : name.trim().toLowerCase();
        String rel = REFERENCES.get(key);
        if (rel == null) {
            return "未知的文档名『" + name + "』。可选：" + String.join("、", REFERENCES.keySet());
        }
        String body = litviz.readReference(rel);
        if (body == null) {
            String why = litviz.unavailableReason();
            return why != null ? why : "读取失败：" + rel;
        }
        return body;
    }

    // ==================== 出图前的三问 ====================

    @ToolMeta(displayName = "出图前确认", category = "litigation-visual")
    @Tool("Generate the three pre-render confirmation questions (structure / style / emphasis) for a "
            + "semantic map. Show the returned text to the user VERBATIM and wait for their answer — do "
            + "NOT rewrite, shorten or re-order it. The questions are generated deterministically on "
            + "purpose: the consequences of these three answers are enforced by the renderer, so the "
            + "asking must not be left to the model. Then set checkpoint.confirmed / "
            + "checkpoint.emphasis_source in the map and call litigation_render.")
    public String litigation_checkpoint(
            @P("The semantic map, as a JSON object string") String semanticMapJson,
            @P(value = "Element id you propose to mark deep red (optional)", required = false) String suggest
    ) {
        Path tmp = null;
        try {
            tmp = writeTempMap(semanticMapJson);
            LitigationVisualService.Result r = litviz.checkpoint(tmp, suggest);
            if (!r.ok()) return "生成确认问题失败：" + r.error();
            return r.raw().getStr("questions", "");
        } catch (IllegalArgumentException e) {
            return "语义地图不是合法 JSON：" + e.getMessage();
        } catch (Exception e) {
            log.error("litigation_checkpoint failed", e);
            return "生成确认问题失败：" + e.getMessage();
        } finally {
            deleteQuietly(tmp);
        }
    }

    // ==================== 出图 ====================

    @ToolMeta(displayName = "生成诉讼图", category = "litigation-visual", fileEffect = "ADDED",
            fileArg = "diagramName", refreshFiles = true)
    @Tool("Draw a litigation diagram (timeline / flowchart / party-relationship) from a semantic map and "
            + "save it into the project. NEVER hand-write SVG coordinates or lay out nodes by eye — emit "
            + "the JSON map and this tool computes ALL geometry. Layouts: numbered_point_timeline (order "
            + "only; the safe default), dated_point_timeline (real date gaps carry meaning), "
            + "proportional_gantt (periods that overlap, e.g. 诉讼时效/保证期间), graphviz_flow (procedure "
            + "with decisions), graphviz_relation (free-form party network), relation_tree (top-down "
            + "hierarchy, e.g. 股权/控制结构), comparison_table (A vs B). Text must be VERBATIM from the "
            + "source — never reorder events, merge items or invent a date. Call litigation_checkpoint "
            + "first; an unconfirmed map is written as a *-draft on purpose.")
    public String litigation_render(
            @P("The semantic map, as a JSON object string (see litigation_reference 'schema')") String semanticMapJson,
            @P("Diagram name, used for the output folder and file names (e.g. '担保纠纷事实经过时间轴')") String diagramName,
            @P("Project ID") Long projectId,
            @P(value = "Target folder ID (optional; omit for project root)", required = false) Long parentFolderId,
            @P(value = "Visual mode: 奇川风 (default, colour) / 歸藏风 (online, lecture) / 白描 (pure B&W print)",
                    required = false) String mode,
            @P(value = "Comma-separated formats (optional; default svg,png,pptx,drawio,vsdx)",
                    required = false) String formats
    ) {
        if (projectId == null) return "Error: projectId is required.";
        String why = litviz.unavailableReason();
        if (why != null) return "诉讼可视化不可用：" + why;

        Path tmpMap = null;
        Path work = null;
        try {
            tmpMap = writeTempMap(semanticMapJson);

            String safeName = sanitize(diagramName, "诉讼图");
            work = Files.createTempDirectory("litviz-out-");
            Path outBase = work.resolve(safeName);

            LitigationVisualService.Result r = litviz.render(
                    tmpMap, outBase, mode,
                    (formats == null || formats.isBlank()) ? DEFAULT_FORMATS : formats);
            if (!r.ok()) {
                return "出图失败：" + r.error()
                        + (r.stderr().isBlank() ? "" : "\n引擎输出：\n" + tail(r.stderr(), 1200));
            }

            JSONArray files = r.raw().getJSONArray("files");
            if (files == null || files.isEmpty()) return "出图失败：引擎没有产出任何文件。";

            // 引擎的 PNG 依赖外部光栅器，桌面端不随包分发，所以多数机器上这一项是空的。
            // 用 Batik 在服务端补上——没有位图的话这张图就插不进用户正在写的文书
            // （doc_insert_image 只收 jpg/png/gif/bmp/webp）。
            List<Path> enginePaths = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                enginePaths.add(Path.of(files.getJSONObject(i).getStr("path")));
            }
            for (Path extra : pngService.ensurePngFor(enginePaths)) {
                files.add(JSONUtil.createObj()
                        .set("format", "png")
                        .set("path", extra.toString())
                        .set("bytes", Files.size(extra)));
            }

            boolean draft = r.raw().getBool("draft", false);
            // 一图一文件夹：一次出五种格式，摊平在项目根下会把文件树冲垮。
            String folderName = sanitize(r.raw().getStr("basename", safeName), safeName);
            ProjectFile folder = createFolderTolerant(projectId, parentFolderId, folderName);

            List<String> registered = new ArrayList<>();
            ProjectFile svg = null;
            for (int i = 0; i < files.size(); i++) {
                JSONObject f = files.getJSONObject(i);
                Path src = Path.of(f.getStr("path"));
                ProjectFile pf = registerArtifact(projectId, folder.getId(), src, MARKER_ARTIFACT);
                if (pf == null) continue;
                registered.add(pf.getName());
                if (svg == null && pf.getName().endsWith(".svg") && !pf.getName().endsWith(".drawio.svg")) {
                    svg = pf;
                }
            }
            if (registered.isEmpty()) return "出图失败：产物未能写入项目。";

            // 语义地图与产物同放。图是从它算出来的，留着才能「换个风格重出一版」而
            // 不必再问一次模型——重问既费钱，也可能因为模型这次读得不一样而改了内容。
            Path mapCopy = work.resolve(folderName + ".map.json");
            Files.copy(tmpMap, mapCopy, StandardCopyOption.REPLACE_EXISTING);
            registerArtifact(projectId, folder.getId(), mapCopy, MARKER_MAP);

            editorBridgeService.sendRefreshFilesAction();
            // SVG 是母版，也是浏览器能原生渲染的那个——自动打开它，用户不必自己去点。
            if (svg != null) editorBridgeService.sendOpenFileAction(svg);

            return buildDeliveryNote(r.raw(), folder, registered, draft);

        } catch (IllegalArgumentException e) {
            return "语义地图不是合法 JSON：" + e.getMessage();
        } catch (Exception e) {
            log.error("litigation_render failed", e);
            return "出图失败：" + e.getMessage();
        } finally {
            deleteQuietly(tmpMap);
            deleteTreeQuietly(work);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 交付说明。刻意把「用了哪种模式、红色标了哪里、有什么存疑」写进回复而不是画进图里：
     * 图上任何解释性文字都会跟着进诉讼材料，而这些是给律师看的过程信息。
     */
    private String buildDeliveryNote(JSONObject raw, ProjectFile folder,
                                     List<String> files, boolean draft) {
        StringBuilder sb = new StringBuilder();
        sb.append(draft ? "已生成草稿图" : "已生成诉讼图")
          .append("『").append(raw.getStr("title", folder.getName())).append("』");
        sb.append("，放在文件夹『").append(folder.getName())
          .append("』（folderId=").append(folder.getId()).append("）。\n");
        sb.append("视觉模式：").append(raw.getStr("mode", "")).append("；")
          .append("布局：").append(raw.getStr("layout", "")).append("。\n");
        sb.append("交付文件：").append(String.join("、", files)).append("\n");
        sb.append("其中 .svg 是母版（已在编辑器打开），.pptx / .drawio / .vsdx 是可以逐个图形"
                + "接着改的源文件（PowerPoint、WPS、draw.io、ProcessOn、Visio 都能开）。\n");
        // PNG 是插进文书用的那一份：doc_insert_image 只收位图，不收 svg。
        // 引擎的 PNG 依赖外部光栅器（桌面端不带），所以服务端用 Batik 兜底补上；
        // 真的一张都没有时说清楚，别让用户对着少掉的文件猜。
        if (files.stream().anyMatch(n -> n.endsWith(".png"))) {
            sb.append("要把图放进正在写的文书，用 doc_insert_image 插那张 .png"
                    + "（它只收位图，不认 .svg）。\n");
        } else {
            sb.append("没有 .png：这台机器上的光栅化没成功。图本身没问题，"
                    + ".svg 母版照常可看可打印；只是暂时插不进 Word 文书。\n");
        }
        if (draft) {
            sb.append("\n注意：这是**草稿**（文件名带 -draft）。语义地图里 checkpoint.confirmed 不为 true，"
                    + "引擎按设计不出终稿——未经用户确认的读法不应当作终稿归档。"
                    + "请把 litigation_checkpoint 的三问原样给用户，拿到答复后再出一次。\n");
        }
        String audit = raw.getStr("audit", "");
        if (!audit.isBlank()) {
            sb.append("\n引擎审计摘要：\n").append(tail(audit, 900));
        }
        return sb.toString();
    }

    /** 同名文件夹已存在时复用而不是报错——同一张图重出一版是常态。 */
    private ProjectFile createFolderTolerant(Long projectId, Long parentId, String name) {
        try {
            return projectFileService.createFolder(projectId, parentId, name, AGENT_USER_ID);
        } catch (IllegalArgumentException e) {
            List<ProjectFile> siblings =
                    projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, parentId);
            for (ProjectFile s : siblings) {
                if (name.equals(s.getName()) && Boolean.TRUE.equals(s.getIsFolder())) return s;
            }
            throw e;
        }
    }

    /** 把引擎产物搬进项目存储并登记进文件树。失败只跳过这一个文件，不毁掉整次交付。 */
    private ProjectFile registerArtifact(Long projectId, Long folderId, Path src, String marker) {
        try {
            String name = src.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            ProjectFile pf = projectFileService.createFile(
                    projectId, folderId, name, ext, Files.size(src), null,
                    marker + projectId + "_" + System.currentTimeMillis(), AGENT_USER_ID);
            Path target = storageResolver.resolve(pf.getFilePath());
            Files.createDirectories(target.getParent());
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
            pf.setFileSize(Files.size(target));
            projectFileRepository.save(pf);
            return pf;
        } catch (Exception e) {
            log.warn("登记出图产物失败，跳过：{}", src, e);
            return null;
        }
    }

    private Path writeTempMap(String json) throws Exception {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("语义地图不能为空");
        }
        // 先在 Java 侧解析一次：JSON 写坏是模型最常见的失误，
        // 让它在这里得到一句人话，而不是一段 Python traceback。
        JSONObject parsed;
        try {
            parsed = JSONUtil.parseObj(json.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (!parsed.containsKey("layout")) {
            throw new IllegalArgumentException("缺少 layout 字段（先用 litigation_reference('schema') 查字段）");
        }
        Path tmp = Files.createTempFile("litviz-map-", ".json");
        Files.writeString(tmp, parsed.toString(), StandardCharsets.UTF_8);
        return tmp;
    }

    /** 文件名净化。图名来自案件标题，斜杠、冒号这类字符很常见，直接建目录会失败。 */
    private static String sanitize(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return fallback;
        return s.length() > 60 ? s.substring(0, 60).trim() : s;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
