package com.checkba.service.ai.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.LitigationVisualService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间轴大师（mqc-timeline-master，vendor 见 litviz/UPSTREAM.md）的工具面。
 *
 * <p>与 {@link LitigationVisualTools}（重画引擎）的分工沿用上游：**重画吃现成的
 * 语义地图，时间轴大师直接吃原始材料**。这条管线是分段的、以工作目录为状态的：
 * 代码步读写 state.json，模型步产出四份 JSON（verdicts/parts/skeleton/items），
 * 中间穿插最多五轮用户勾选（材料/时间段/风格/取材/标红）。
 *
 * <p>三条不许绕的结构约束：
 * <ul>
 * <li><b>一个会话一个工作目录。</b>管线全部状态文件是 cwd 下的固定文件名，
 *     并发共用一个目录就是互相踩踏。目录由本类管理（LRU 封顶，挤出即删），
 *     模型看不到路径、也不需要看到。</li>
 * <li><b>模型产出一律走 modelFilesJson 参数提交</b>，由本类写进工作目录。
 *     不要让模型用 write_file 落项目文件——与 v1 语义地图同一条教训。</li>
 * <li><b>材料先由 Java 侧转成 UTF-8 文本。</b>上游 read_source 只认 UTF-8 文本与
 *     python-docx（打包运行时没有），Tika 抽取是我们已有的能力；扫描件让模型先走
 *     OCR 工具出文字版再进管线——正合上游 ADR 0002「转写稿作为材料」。</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LitigationTimelineTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LitigationTimelineTools.class);

    /** 模型可提交的文件名白名单——管线认识的那几份，别的名字一律拒收。 */
    private static final Set<String> MODEL_FILES = Set.of(
            "verdicts.json", "parts.json", "skeleton.json", "items.json",
            "lane_labels.json", "lane_sides.json", "lane_relations.json", "image_docs.json");

    /** litigation_timeline_step 可驱动的阶段。read/render 各有专职工具，不在此列。 */
    private static final Set<String> STEP_STAGES = Set.of(
            "pick", "span", "style", "offer", "budget", "capacity",
            "title", "mark", "next", "steps", "shape");

    /**
     * 句子清单的总字符上限。verdicts 要求逐句判定、一句不漏，所以句子必须全量
     * 给到模型；超过这个量的材料在一轮上下文里也判不好，不如明确要求缩小范围。
     */
    private static final int MAX_SENTENCE_CHARS = 80_000;

    /** 一次时间轴会话：工作目录 + 渲染序号 + 幂等指纹。 */
    private static final class Session {
        Path workdir;
        int renderSeq;
        String lastRenderFingerprint;
        String lastRenderNote;
    }

    /**
     * conversationId → 会话。LRU 封顶：工作目录是真实磁盘占用，被挤出去的连目录
     * 一起删。32 个并发时间轴会话已远超真实使用。
     */
    private static final Map<String, Session> SESSIONS = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
                    if (size() > 32) {
                        deleteTreeQuietly(eldest.getValue().workdir);
                        return true;
                    }
                    return false;
                }
            });

    private final LitigationVisualService litviz;
    private final com.checkba.service.ai.LitigationPngService pngService;
    private final DocumentTextService documentTextService;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final EditorBridgeService editorBridgeService;
    private final LitigationVisualTools visualTools;

    // ==================== 开始：读材料 ====================

    @ToolMeta(displayName = "读入时间轴材料", category = "litigation-visual")
    @Tool("Start the timeline-master pipeline: turn raw case materials (judgment, complaint, "
            + "defence, contracts, evidence lists, bank statements, chat logs...) into a faithful "
            + "case timeline. Pass the project file IDs of the materials (comma separated; a folder "
            + "ID expands to its direct children). This tool extracts their text, feeds the "
            + "deterministic pipeline, and returns the NUMBERED sentence list you will later judge "
            + "sentence by sentence. Files with no extractable text (scans/photos) are reported "
            + "back — run OCR first and add the text version as a material. Use this ONLY when the "
            + "user wants a timeline built FROM materials; for redrawing an existing figure or the "
            + "other six layouts use litigation_render.")
    public String litigation_timeline_start(
            @P("Project ID") Long projectId,
            @P("Comma-separated project file IDs of the case materials") String materialFileIds
    ) {
        if (projectId == null) return "Error: projectId is required.";
        String why = litviz.unavailableReason();
        if (why != null) return "诉讼可视化不可用：" + why;
        if (materialFileIds == null || materialFileIds.isBlank()) {
            return "Error: materialFileIds is required（用 doc_list_project_files / list_files 拿文件 ID）。";
        }

        try {
            // 重新 start = 新开一张图：旧目录整个换掉，不让上一张图的状态渗进来。
            Session s = new Session();
            s.workdir = Files.createTempDirectory("litviz-timeline-");
            Session old = SESSIONS.put(sessionKey(), s);
            if (old != null) deleteTreeQuietly(old.workdir);

            Path materialsDir = s.workdir.resolve("materials");
            Files.createDirectories(materialsDir);

            List<String> relPaths = new ArrayList<>();
            List<String> unreadable = new ArrayList<>();
            int idx = 0;
            for (ProjectFile pf : resolveMaterials(materialFileIds)) {
                String text;
                try {
                    text = documentTextService.extractText(pf);
                } catch (Exception e) {
                    unreadable.add(pf.getName() + "（抽取失败：" + e.getMessage() + "）");
                    continue;
                }
                if (text == null || text.isBlank()) {
                    unreadable.add(pf.getName() + "（没有可提取文本，可能是扫描件/照片）");
                    continue;
                }
                idx++;
                // 保留原名（律师认材料靠名字，pick 清单与溯源索引都会显示它），
                // 前缀序号防同名，统一 .txt 后缀表明这是转出的文字版。
                String safe = LitigationVisualTools.sanitize(pf.getName(), "材料" + idx);
                Path dst = materialsDir.resolve(String.format("%02d-%s.txt", idx, safe));
                Files.writeString(dst, text, StandardCharsets.UTF_8);
                relPaths.add("materials/" + dst.getFileName());
            }
            if (relPaths.isEmpty()) {
                SESSIONS.remove(sessionKey());
                deleteTreeQuietly(s.workdir);
                return "没有一份材料能读出文本。" + String.join("；", unreadable)
                        + "\n扫描件/照片请先用 OCR（如 pdf_to_word 的 OCR 路线）转出文字版存入项目，再重新开始。";
            }

            LitigationVisualService.Result r = litviz.timeline(s.workdir, "read", relPaths, null);
            if (!r.ok()) {
                return "读入材料失败：" + errorOf(r);
            }

            String sentenceList = numberedSentences(s.workdir);
            if (sentenceList == null) {
                return "读入材料失败：管线没有落下 state.json。" + tailStderr(r);
            }
            if (sentenceList.length() > MAX_SENTENCE_CHARS) {
                SESSIONS.remove(sessionKey());
                deleteTreeQuietly(s.workdir);
                return "材料过大（句子清单超过 " + (MAX_SENTENCE_CHARS / 1000) + "K 字符）。"
                        + "请缩小材料范围（挑与这张图直接相关的几份），或分几张图来画。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(r.raw().getStr("text", "")).append("\n\n");
            if (!unreadable.isEmpty()) {
                sb.append("以下材料本轮没有读入：").append(String.join("；", unreadable))
                  .append("\n需要它们的话，先用 OCR 出文字版并存入项目，再重新 litigation_timeline_start。\n\n");
            }
            sb.append("── 句子清单（后面所有判定都按这些编号对账）──\n").append(sentenceList);
            sb.append(START_GUIDANCE);
            return sb.toString();
        } catch (Exception e) {
            log.error("litigation_timeline_start failed", e);
            return "读入材料失败：" + e.getMessage();
        }
    }

    // ==================== 中段：勾选与模型产出 ====================

    @ToolMeta(displayName = "推进时间轴管线", category = "litigation-visual")
    @Tool("Advance the timeline-master pipeline by ONE stage. Stages: pick (round 1, which "
            + "materials, answer like '1,2' or '全部'), span (round 2, time span), style (round 3: "
            + "1 奇川风 / 2 歸藏风 / 3 白描 — NEVER leave empty, default to '1'), offer (submit "
            + "verdicts.json + parts.json via modelFilesJson first), budget (round 4, which parts), "
            + "capacity (submit skeleton.json first), title (the figure title as answer), mark "
            + "(round 5, crimson accent: event id or '0' for none; set emphasisSource honestly), "
            + "next/steps (where am I). The pipeline text it returns contains the exact question to "
            + "relay to the user via <question>/<option> and the instructions for your next move. "
            + "Model-produced JSON files are ALWAYS submitted through modelFilesJson — never via "
            + "write_file.")
    public String litigation_timeline_step(
            @P("Pipeline stage name") String stage,
            @P(value = "The answer / positional argument for this stage (e.g. '1,2', '全部', '3', a title)",
                    required = false) String answer,
            @P(value = "JSON object mapping file name to its JSON content, e.g. "
                    + "{\"verdicts.json\": [...], \"parts.json\": [...]}", required = false) String modelFilesJson,
            @P(value = "mark stage only — who chose the crimson accent: user / model / none",
                    required = false) String emphasisSource
    ) {
        String st = stage == null ? "" : stage.trim().toLowerCase();
        if (!STEP_STAGES.contains(st)) {
            return "未知阶段『" + stage + "』。可用：" + String.join("、", STEP_STAGES.stream().sorted().toList())
                    + "（读材料用 litigation_timeline_start，出图用 litigation_timeline_render）。";
        }
        Session s = SESSIONS.get(sessionKey());
        if (s == null || !Files.isDirectory(s.workdir)) {
            return "时间轴会话不存在或已过期。先调 litigation_timeline_start 读入材料。";
        }
        try {
            String fileErr = writeModelFiles(s.workdir, modelFilesJson);
            if (fileErr != null) return fileErr;

            List<String> args = new ArrayList<>();
            String a = answer == null ? "" : answer.trim();
            if ("style".equals(st) && a.isEmpty()) {
                // 上游 CLI 裸跑 style 的默认值是 2（歸藏风），与它自己文档承诺的
                // 「不回 = 奇川风」相悖（契约盘点第 9 节）。这里把默认钉回 1。
                a = "1";
            }
            if ("mark".equals(st) && a.isEmpty()) {
                a = "0";
            }
            if (!a.isEmpty()) args.add(a);

            LitigationVisualService.Result r = litviz.timeline(
                    s.workdir, st, args, "mark".equals(st) ? emphasisSource : null);
            String text = r.raw() == null ? "" : r.raw().getStr("text", "");
            if (!r.ok()) {
                return "阶段 " + st + " 未通过：\n" + (text.isBlank() ? errorOf(r) : text)
                        + "\n\n──给你（AI）──按上面指名的问题修正后重试本阶段；"
                        + "改模型产出就把修正后的整份 JSON 重新经 modelFilesJson 提交。";
            }
            return text + STEP_GUIDANCE;
        } catch (Exception e) {
            log.error("litigation_timeline_step failed: stage={}", st, e);
            return "阶段 " + st + " 执行失败：" + e.getMessage();
        }
    }

    // ==================== 出图 ====================

    @ToolMeta(displayName = "生成案件时间轴", category = "litigation-visual", fileEffect = "ADDED",
            fileArg = "diagramName", refreshFiles = true)
    @Tool("Render the timeline and save it into the project. Requires the pipeline to have reached "
            + "the items stage (items.json submitted here via modelFilesJson or in a previous step). "
            + "Every head text must be a pure-deletion subsequence of its source sentences — the "
            + "pipeline rejects any rewording before drawing. Delivers svg/png/pptx/vsdx/drawio "
            + "plus a traceability index (Word) into one folder named after the figure. Call ONCE; "
            + "if it rejects (over capacity / fidelity), fix items.json and call again.")
    public String litigation_timeline_render(
            @P("Project ID") Long projectId,
            @P("Figure name, used for the folder and file names (e.g. '催告与还款经过时间轴')") String diagramName,
            @P(value = "Target folder ID (optional; omit for project root)", required = false) Long parentFolderId,
            @P(value = "JSON object with items.json (and other pipeline files) to submit before "
                    + "rendering", required = false) String modelFilesJson
    ) {
        if (projectId == null) return "Error: projectId is required.";
        Session s = SESSIONS.get(sessionKey());
        if (s == null || !Files.isDirectory(s.workdir)) {
            return "时间轴会话不存在或已过期。先调 litigation_timeline_start 读入材料。";
        }
        try {
            String fileErr = writeModelFiles(s.workdir, modelFilesJson);
            if (fileErr != null) return fileErr;

            Path itemsFile = s.workdir.resolve("items.json");
            if (!Files.isRegularFile(itemsFile)) {
                return "还没有 items.json——先按 capacity 给出的容量写好每个事项的 head，"
                        + "经 modelFilesJson 提交（键名 items.json）。";
            }

            String safeName = LitigationVisualTools.sanitize(diagramName, "案件时间轴");
            String fingerprint = Integer.toHexString(
                    (Files.readString(itemsFile, StandardCharsets.UTF_8) + "|" + safeName + "|" + parentFolderId)
                            .hashCode());
            if (fingerprint.equals(s.lastRenderFingerprint) && s.lastRenderNote != null) {
                return s.lastRenderNote
                        + "\n\n[本次调用没有重新出图] 同一份 items 刚出过图，文件都在上面那个文件夹里。"
                        + "直接把交付说明讲给用户即可。";
            }

            s.renderSeq++;
            String outRel = "out-" + s.renderSeq + "/" + safeName + ".svg";
            LitigationVisualService.Result r = litviz.timeline(s.workdir, "render", List.of(outRel), null);
            String text = r.raw() == null ? "" : r.raw().getStr("text", "");
            if (!r.ok()) {
                return "出图被拦下：\n" + (text.isBlank() ? errorOf(r) : text)
                        + "\n\n──给你（AI）──按指名的事项改 items.json（超容量就删字、忠实性不过就只删不改），"
                        + "重新经 modelFilesJson 提交并再调一次本工具。";
            }

            JSONArray files = r.raw().getJSONArray("files");
            if (files == null || files.isEmpty()) return "出图失败：管线没有报出任何产物。";

            List<Path> paths = new ArrayList<>();
            Path traceJson = null;
            for (int i = 0; i < files.size(); i++) {
                Path p = Path.of(files.getJSONObject(i).getStr("path"));
                if (p.getFileName().toString().endsWith("-trace.json")) {
                    traceJson = p;
                } else {
                    paths.add(p);
                }
            }
            // 引擎侧 PNG 依赖外部光栅器（用户机器多半没有），Batik 兜底——没有位图，
            // 图就插不进正在写的文书（doc_insert_image 只收位图）。
            paths.addAll(pngService.ensurePngFor(new ArrayList<>(paths)));
            // 溯源索引：管线里那条 node 路线在桌面端不可用（不随包分发 node/docx 包），
            // 由服务端按 trace.json 用 POI 出同一份 Word 三线表。已有 docx 时不重复出。
            if (traceJson != null && paths.stream().noneMatch(p -> p.getFileName().toString().endsWith(".docx"))) {
                Path docx = buildTraceDocx(traceJson, safeName);
                if (docx != null) paths.add(docx);
            }

            ProjectFile folder = visualTools.createFolderTolerant(projectId, parentFolderId, safeName);
            List<String> registered = new ArrayList<>();
            ProjectFile svg = null;
            ProjectFile drawio = null;
            for (Path p : paths.stream().sorted(Comparator.comparing(x -> x.getFileName().toString())).toList()) {
                ProjectFile pf = visualTools.registerArtifact(
                        projectId, folder.getId(), p, LitigationVisualTools.MARKER_ARTIFACT);
                if (pf == null) continue;
                registered.add(pf.getName());
                if (svg == null && pf.getName().endsWith(".svg") && !pf.getName().endsWith(".drawio.svg")) svg = pf;
                if (drawio == null && pf.getName().endsWith(".drawio")) drawio = pf;
            }
            if (registered.isEmpty()) return "出图失败：产物未能写入项目。";

            editorBridgeService.sendRefreshFilesAction();
            ProjectFile openTarget = drawio != null ? drawio : svg;
            if (openTarget != null) editorBridgeService.sendOpenFileAction(openTarget);

            String note = buildDeliveryNote(text, folder.getName(), folder.getId(), registered);
            s.lastRenderFingerprint = fingerprint;
            s.lastRenderNote = note;
            return note;
        } catch (Exception e) {
            log.error("litigation_timeline_render failed", e);
            return "出图失败：" + e.getMessage();
        }
    }

    // ==================== 辅助 ====================

    private String buildDeliveryNote(String pipelineText, String folderName, Long folderId,
                                     List<String> registered) {
        StringBuilder sb = new StringBuilder();
        sb.append("已生成案件时间轴，放在文件夹『").append(folderName)
          .append("』（folderId=").append(folderId).append("）。\n");
        sb.append("交付文件：").append(String.join("、", registered)).append("\n");
        boolean editable = registered.stream().anyMatch(n -> n.endsWith(".drawio"));
        sb.append(editable
                ? "其中 .drawio 是可以接着改的源文件，已在编辑器里打开；.svg 是母版，看和打印用。\n"
                : "其中 .svg 是母版（已在编辑器打开）。\n");
        if (registered.stream().anyMatch(n -> n.endsWith(".png"))) {
            sb.append("要把图放进正在写的文书，用 doc_insert_image 插那张 .png（它只收位图）。\n");
        } else {
            sb.append("没有 .png：这台机器上的光栅化没成功，.svg 母版照常可看可打印。\n");
        }
        if (registered.stream().anyMatch(n -> n.contains("溯源索引"))) {
            sb.append("溯源索引（Word 三线表）也在文件夹里：图上每个元素出自材料的哪一份、哪一句，"
                    + "打印出来可夹在卷宗里逐项核对。\n");
        }
        if (pipelineText != null && !pipelineText.isBlank()) {
            sb.append("\n管线交付说明：\n").append(LitigationVisualTools.tail(pipelineText, 900)).append("\n");
        }
        sb.append("\n[本图已交付] 不要再调用 litigation_timeline_render（除非用户要求改内容）。"
                + "向用户交付时说清楚：图种与泳道是算出来的、深红标在哪一处（或未标、是谁定的）、"
                + "哪些事项出自读图需要用户回原件复核。");
        return sb.toString();
    }

    /** 解析材料 ID 清单；文件夹展开一层直接子文件。 */
    private List<ProjectFile> resolveMaterials(String ids) {
        List<ProjectFile> out = new ArrayList<>();
        for (String tok : ids.split("[,，、\\s]+")) {
            if (tok.isBlank()) continue;
            long id;
            try {
                id = Long.parseLong(tok.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            ProjectFile pf = projectFileRepository.findById(id).orElse(null);
            if (pf == null) continue;
            if (ToolFileGuard.rejectIfOutsideProject(pf) != null) continue;
            if (Boolean.TRUE.equals(pf.getIsFolder()) || "folder".equalsIgnoreCase(pf.getFileType())) {
                for (ProjectFile child : projectFileRepository
                        .findByProjectIdAndParentIdOrderBySortOrderAsc(pf.getProjectId(), pf.getId())) {
                    if (!Boolean.TRUE.equals(child.getIsFolder())
                            && ToolFileGuard.rejectIfOutsideProject(child) == null) {
                        out.add(child);
                    }
                }
            } else {
                out.add(pf);
            }
        }
        return out;
    }

    /** 把模型经参数提交的 JSON 文件写进工作目录。返回错误消息，或 null 表示成功/无事可做。 */
    private static String writeModelFiles(Path workdir, String modelFilesJson) {
        if (modelFilesJson == null || modelFilesJson.isBlank()) return null;
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(modelFilesJson.trim());
        } catch (Exception e) {
            return "modelFilesJson 不是合法 JSON 对象：" + e.getMessage();
        }
        for (String name : obj.keySet()) {
            if (!MODEL_FILES.contains(name)) {
                return "不认识的文件名『" + name + "』。可提交：" + String.join("、", MODEL_FILES.stream().sorted().toList());
            }
        }
        try {
            for (String name : obj.keySet()) {
                Files.writeString(workdir.resolve(name),
                        JSONUtil.toJsonStr(obj.get(name)), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "写入模型产出失败：" + e.getMessage();
        }
        return null;
    }

    /** 从 state.json 取逐句编号清单（管线只报句数，模型判定要看原句）。 */
    private static String numberedSentences(Path workdir) {
        try {
            Path state = workdir.resolve("state.json");
            if (!Files.isRegularFile(state)) return null;
            JSONObject st = JSONUtil.parseObj(Files.readString(state, StandardCharsets.UTF_8));
            JSONArray sentences = st.getJSONArray("sentences");
            if (sentences == null) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sentences.size(); i++) {
                sb.append(i).append(": ").append(sentences.getStr(i)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按管线落的 trace.json 出溯源索引 Word（三线表）。payload 形状由上游
     * pipeline._trace 固定：title/note/foot/rows[{no,head,file,locator,quote}]。
     * 出不来只损失这一份附件，绝不拖垮整次交付。
     */
    static Path buildTraceDocx(Path traceJson, String figureName) {
        try {
            JSONObject payload = JSONUtil.parseObj(Files.readString(traceJson, StandardCharsets.UTF_8));
            JSONArray rows = payload.getJSONArray("rows");
            if (rows == null || rows.isEmpty()) return null;

            try (XWPFDocument doc = new XWPFDocument()) {
                XWPFParagraph title = doc.createParagraph();
                title.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun tr = title.createRun();
                tr.setText(payload.getStr("title", figureName + " · 溯源索引"));
                tr.setBold(true);
                tr.setFontSize(14);

                String note = payload.getStr("note", "");
                if (!note.isBlank()) {
                    XWPFRun nr = doc.createParagraph().createRun();
                    nr.setText(note);
                    nr.setFontSize(9);
                }

                XWPFTable table = doc.createTable(rows.size() + 1, 5);
                String[] heads = {"序号", "图上的元素", "出自材料", "位置", "核验方式"};
                XWPFTableRow head = table.getRow(0);
                for (int c = 0; c < heads.length; c++) {
                    setCellText(head.getCell(c), heads[c], true);
                }
                String[] keys = {"no", "head", "file", "locator", "quote"};
                for (int i = 0; i < rows.size(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    XWPFTableRow trow = table.getRow(i + 1);
                    for (int c = 0; c < keys.length; c++) {
                        setCellText(trow.getCell(c), row.getStr(keys[c], ""), false);
                    }
                }
                threeLineBorders(table);

                String foot = payload.getStr("foot", "");
                if (!foot.isBlank()) {
                    XWPFRun fr = doc.createParagraph().createRun();
                    fr.setText(foot);
                    fr.setFontSize(9);
                }

                Path out = traceJson.getParent().resolve(figureName + "-溯源索引.docx");
                try (OutputStream os = Files.newOutputStream(out)) {
                    doc.write(os);
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("溯源索引 docx 生成失败（不影响出图交付）: {}", traceJson, e);
            return null;
        }
    }

    private static void setCellText(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        XWPFRun r = p.createRun();
        r.setText(text == null ? "" : text);
        r.setFontSize(9);
        r.setBold(bold);
    }

    /** 三线表：只留顶线、表头下线、底线，竖线与其余横线全部去掉。 */
    private static void threeLineBorders(XWPFTable table) {
        var borders = table.getCTTbl().getTblPr().isSetTblBorders()
                ? table.getCTTbl().getTblPr().getTblBorders()
                : table.getCTTbl().getTblPr().addNewTblBorders();
        var none = org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.NONE;
        var single = org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder.SINGLE;
        borders.addNewTop().setVal(single);
        borders.addNewBottom().setVal(single);
        borders.addNewLeft().setVal(none);
        borders.addNewRight().setVal(none);
        borders.addNewInsideV().setVal(none);
        borders.addNewInsideH().setVal(none);
        // 表头下线：给第一行每个格子单独描 bottom
        XWPFTableRow head = table.getRow(0);
        for (XWPFTableCell cell : head.getTableCells()) {
            var tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            var tcBorders = tcPr.isSetTcBorders() ? tcPr.getTcBorders() : tcPr.addNewTcBorders();
            tcBorders.addNewBottom().setVal(single);
        }
    }

    private static String sessionKey() {
        String cid = com.checkba.service.ai.context.ProjectContextHolder.getConversationId();
        return cid == null || cid.isBlank() ? "-" : cid;
    }

    private static String errorOf(LitigationVisualService.Result r) {
        String err = r.error();
        if (err != null && !err.isBlank()) return err;
        return "（无输出）" + tailStderr(r);
    }

    private static String tailStderr(LitigationVisualService.Result r) {
        return r.stderr() == null || r.stderr().isBlank()
                ? "" : "\nstderr：" + LitigationVisualTools.tail(r.stderr(), 600);
    }

    private static void deleteTreeQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    /**
     * start 之后的执行指引。挂在工具返回文本里而不是只写 prompt——skill 注入是按轮的，
     * 用户的勾选答复里没有触发词，指引必须随工具结果留在对话历史里（与 v1 的
     * CHECKPOINT_NEXT_STEPS 同一条经验）。{@code LitigationTimelineFlowTest} 钉住关键句。
     */
    static final String START_GUIDANCE = """


            ────────── 以下是给你（AI）的执行指引，不要发给用户 ──────────
            这是时间轴大师管线：材料已读入，句子清单在上面。**后面每一步的判定都按这些编号对账。**
            管线一步一问，每步调一次 litigation_timeline_step：
            1. stage=pick：把它返回的材料清单用 <question>+<option> 原样问用户，答案（如「1,2」或「全部」）传 answer。只有一份材料时它自动跳过。
            2. stage=span：时间段勾选，同上；它判定不值得问时会自动跳过。
            3. stage=style：问用户这张图呈报给谁——开庭/交法院 → 3（白描），当面讲/交当事人 → 1（奇川风），讲课/对外传播 → 2（歸藏风）。用户不选就传 "1"，**不许留空**。
            4. 然后是你的判定：逐句写 verdicts.json（每句一条 {i,is_event,why}，0..n-1 一句不漏；诉请/约定/主张/标题都不是事实）与 parts.json（划分部分），随 stage=offer 经 modelFilesJson 一并提交。
            5. offer 返回的部分清单用 <question> 问用户（整份还是取几段），答案传 stage=budget。
            6. budget 后写 skeleton.json（只写骨架不写正文：certainty 按材料精度四档、raw 逐字可回句中查、kind 八选一、承诺与约定的时点不进主轴），随 stage=capacity 提交。
            7. capacity 给出每块容量；奇川风时还会列深红候选——用 <question> 问用户标哪一处（0=不标）。用户说「你定」就自己挑，stage=mark 时 emphasisSource 传 model，交付时说明是你挑的。
            8. stage=title 提交图名（不超它给的字数）。
            9. 按容量写 items.json（head 必须是原句删字后的子序列，不换词不调序不补字），调 litigation_timeline_render 出图。
            纪律：这些 JSON 一律经 modelFilesJson 参数提交，**不要用 write_file 落项目文件**；图种、层数、泳道、容量全是算出来的，不要替用户和管线做这些决定。""";

    /** 每个中段阶段的收尾提醒：管线自己的「下一步」提示是权威，这里只补协议一句。 */
    static final String STEP_GUIDANCE = """


            ──给你（AI）──上面若含要问用户的清单，用 <question>+<option> 原样转述后停下等回复；
            「下一步」提示是给你的，按它走。模型产出一律经 modelFilesJson 提交。""";
}
