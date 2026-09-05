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

    /**
     * 默认交付四件：svg 是母版（图廊识别、换风格都靠它），png 是唯一能插进
     * 正在写的起诉状的格式（doc_insert_image 只收位图），drawio 是可编辑版。
     * 第四件 map.json 不在这个逗号列表里——它由 render 方法单独落盘，
     * 丢了就没法「换风格」时不重新问模型。
     */
    private static final String DEFAULT_FORMATS = "svg,png,drawio";

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

    /**
     * 产物 wpsFileId 的进程内序号。
     *
     * <p>原来只用 {@code System.currentTimeMillis()}：一次出图的四五个文件在同一个
     * for 循环里登记，毫秒级完全撞得上，于是它们共享同一个 wpsFileId。
     * {@code /api/files/{id}/download} 在按数字 id 查不到时会退回
     * {@code findByWpsFileId(...).findFirst()}——撞号意味着"按 wpsFileId 下载 .drawio"
     * 可能拿到同一张图的 .svg 或 .map.json。加一个单调序号把身份还原成唯一。
     */
    private static final java.util.concurrent.atomic.AtomicLong MARKER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 生成一个唯一的产物身份标记（见 {@link #MARKER_SEQ}）。 */
    public static String newMarker(String prefix, Long projectId) {
        return prefix + projectId + "_" + System.currentTimeMillis()
                + "_" + MARKER_SEQ.incrementAndGet();
    }

    /**
     * 会话级流程状态。
     *
     * <p>为什么需要它：skill 的 prompt 注入与工具白名单是<b>按轮</b>生效的
     * （SkillRouter.activateForTurn 每条用户消息重算一次）。用户那句"确认，就这样"
     * 里没有任何触发词，于是恰恰在"回填 checkpoint 然后出图"这一步，整份诉讼可视化
     * 指引从上下文里消失了。真机表现就是：确认后模型改去 write_file 存地图、
     * 忘了调 litigation_render，或者连着渲染两次。
     *
     * <p>工具返回文本是留在对话历史里的，不随 skill 失活而消失——所以"下一步做什么"
     * 的确定性引导挂在这里，而不是只写进 prompt。状态本身再兜一层：重复的
     * checkpoint 不重新跑脚本，重复的 render 不重复出图。
     */
    private static final class TurnState {
        String pendingCheckpointFingerprint;
        String pendingCheckpointQuestions;
        int checkpointCalls;
        String lastRenderFingerprint;
        String lastRenderNote;
    }

    /**
     * conversationId -> 流程状态。容量封顶的 LRU：条目只是几个短字符串，
     * 但没有生命周期钩子来清理，不封顶就是一处慢性泄漏。
     */
    private static final Map<String, TurnState> TURN_STATES = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TurnState> eldest) {
                    return size() > 200;
                }
            });

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
            + "semantic map. Pass the map INLINE as a JSON string — never save it to a project file "
            + "with write_file and never re-read it with read_file; it stays in this conversation. "
            + "Show the question block to the user VERBATIM and wait for their answer — do NOT rewrite, "
            + "shorten or re-order it. The questions are generated deterministically on purpose: the "
            + "consequences of these three answers are enforced by the renderer, so the asking must not "
            + "be left to the model. Call this ONCE per confirmation round; calling it again before the "
            + "user has replied returns the same questions and nothing else. Then set "
            + "checkpoint.confirmed / checkpoint.emphasis_source in the same JSON and call "
            + "litigation_render exactly once.")
    public String litigation_checkpoint(
            @P("The semantic map, as a JSON object string") String semanticMapJson,
            @P(value = "Element id you propose to mark deep red (optional)", required = false) String suggest
    ) {
        TurnState st = turnState();
        Path tmp = null;
        try {
            tmp = writeTempMap(semanticMapJson);
            String fingerprint = fingerprint(Files.readString(tmp, StandardCharsets.UTF_8));

            // 幂等：三问已经发出、用户还没回话时，重复调用不重新跑脚本，也不产生新问题。
            // 真机上这个工具在同一轮里被连调了三次——问题本身是确定性生成的，
            // 第二次之后除了消耗步数预算什么都没发生。
            if (st != null && fingerprint.equals(st.pendingCheckpointFingerprint)
                    && st.pendingCheckpointQuestions != null) {
                st.checkpointCalls++;
                return st.pendingCheckpointQuestions + repeatedCheckpointNotice(st.checkpointCalls);
            }

            LitigationVisualService.Result r = litviz.checkpoint(tmp, suggest);
            if (!r.ok()) return "生成确认问题失败：" + r.error();
            String questions = r.raw().getStr("questions", "");
            if (st != null) {
                st.pendingCheckpointFingerprint = fingerprint;
                st.pendingCheckpointQuestions = questions;
                st.checkpointCalls = 1;
            }
            return questions + CHECKPOINT_NEXT_STEPS;
        } catch (IllegalArgumentException e) {
            return "语义地图不是合法 JSON：" + e.getMessage();
        } catch (Exception e) {
            log.error("litigation_checkpoint failed", e);
            return "生成确认问题失败：" + e.getMessage();
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * 分隔线以下是给模型看的执行指引，不属于要原样转述给用户的三问。
     *
     * <p>为什么挂在工具返回文本里而不是只写进 skill prompt：skill 是按轮生效的，
     * 用户那句"确认"里没有触发词，指引在最关键的一步反而不在上下文里。
     * 工具结果留在历史里，所以这段话在确认后的那一轮依然看得见——与仓内
     * 「约束要挂消息末位」是同一条经验的另一个落点。
     */
    private static final String CHECKPOINT_NEXT_STEPS = """


            ────────── 以下是给你（AI）的执行指引，不要发给用户 ──────────
            1. 把分隔线以上的三问原样发给用户，然后停下等回复。本轮不要再调用 \
            litigation_checkpoint——问题是确定性生成的，再调一次不会有新内容。
            2. 语义地图全程以 JSON 字符串内联传参。**不要用 write_file 把它存成项目文件，\
            也不要用 read_file 读回来**——它已经在本次对话里，落成文件只会让你在下一步找不到它。
            3. 用户回复后，把答复回填进同一份 JSON 的 checkpoint 字段
            （confirmed: true/false、emphasis_source: user/model/none），\
            然后**必须调用一次 litigation_render**，把同一份 JSON 作为 semanticMapJson 传进去。只调一次。
            4. 在 litigation_render 成功返回之前，不要对用户说"图已经生成"——那一步没跑，项目里就没有图。""";

    private static String repeatedCheckpointNotice(int calls) {
        return """


                ────────── 以下是给你（AI）的执行指引，不要发给用户 ──────────
                [重复调用第 %d 次] 这一轮的确认问题已经发出过了，上面是同一份，没有重新生成。
                停止调用 litigation_checkpoint：把三问原样发给用户，结束本轮，等用户回复。
                收到回复后回填 checkpoint 字段并调用一次 litigation_render 出图。""".formatted(calls);
    }

    /** 取本会话的流程状态；拿不到 conversationId（评测/直调）时返回 null，行为退回无状态。 */
    private static TurnState turnState() {
        String cid = com.checkba.service.ai.context.ProjectContextHolder.getConversationId();
        if (cid == null || cid.isBlank()) return null;
        return TURN_STATES.computeIfAbsent(cid, k -> new TurnState());
    }

    private static String fingerprint(String s) {
        return Integer.toHexString(s.hashCode()) + ":" + s.length();
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
            + "source — never reorder events, merge items or invent a date. Pass the map INLINE as a "
            + "JSON string — never stage it through write_file/read_file. Call litigation_checkpoint "
            + "first; an unconfirmed map is written as a *-draft on purpose. After the user confirms, "
            + "call this exactly ONCE — this tool is what actually puts the figure in the project, and "
            + "a second identical call produces nothing new.")
    public String litigation_render(
            @P("The semantic map, as a JSON object string (see litigation_reference 'schema')") String semanticMapJson,
            @P("Diagram name, used for the output folder and file names (e.g. '担保纠纷事实经过时间轴')") String diagramName,
            @P("Project ID") Long projectId,
            @P(value = "Target folder ID (optional; omit for project root)", required = false) Long parentFolderId,
            @P(value = "Visual mode: 奇川风 (default, colour) / 歸藏风 (online, lecture) / 白描 (pure B&W print)",
                    required = false) String mode,
            @P(value = "Comma-separated formats (optional; default svg,png,drawio)",
                    required = false) String formats
    ) {
        if (projectId == null) return "Error: projectId is required.";
        String why = litviz.unavailableReason();
        if (why != null) return "诉讼可视化不可用：" + why;

        TurnState st = turnState();
        Path tmpMap = null;
        Path work = null;
        try {
            tmpMap = writeTempMap(semanticMapJson);

            // 同一份地图、同一个图名/位置/模式连着出两次，只是把同一批文件重写一遍。
            // 真机上出现过"连续渲染两次"，第二次纯属浪费，还会让交付说明重复一遍。
            // 指纹带上模式与落点：换风格、换位置都是真的要再出一版，不会被短路。
            String renderFingerprint = fingerprint(
                    Files.readString(tmpMap, StandardCharsets.UTF_8)
                            + "|" + diagramName + "|" + parentFolderId + "|" + mode + "|" + formats);
            if (st != null && renderFingerprint.equals(st.lastRenderFingerprint)
                    && st.lastRenderNote != null) {
                return st.lastRenderNote
                        + "\n\n[本次调用没有重新出图] 这张图刚刚已经用同一份语义地图出过了，"
                        + "文件都在上面那个文件夹里。不要再调用 litigation_render；"
                        + "直接把交付说明讲给用户即可。";
            }

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
            // 一图一文件夹：一次出四种格式，摊平在项目根下会把文件树冲垮。
            String folderName = sanitize(r.raw().getStr("basename", safeName), safeName);
            ProjectFile folder = createFolderTolerant(projectId, parentFolderId, folderName);

            List<String> registered = new ArrayList<>();
            ProjectFile svg = null;
            ProjectFile drawio = null;
            for (int i = 0; i < files.size(); i++) {
                JSONObject f = files.getJSONObject(i);
                Path src = Path.of(f.getStr("path"));
                ProjectFile pf = registerArtifact(projectId, folder.getId(), src, MARKER_ARTIFACT);
                if (pf == null) continue;
                registered.add(pf.getName());
                if (svg == null && pf.getName().endsWith(".svg") && !pf.getName().endsWith(".drawio.svg")) {
                    svg = pf;
                }
                if (drawio == null && pf.getName().endsWith(".drawio")) {
                    drawio = pf;
                }
            }
            if (registered.isEmpty()) return "出图失败：产物未能写入项目。";

            // 语义地图与产物同放。图是从它算出来的，留着才能「换个风格重出一版」而
            // 不必再问一次模型——重问既费钱，也可能因为模型这次读得不一样而改了内容。
            Path mapCopy = work.resolve(folderName + ".map.json");
            Files.copy(tmpMap, mapCopy, StandardCopyOption.REPLACE_EXISTING);
            registerArtifact(projectId, folder.getId(), mapCopy, MARKER_MAP);

            editorBridgeService.sendRefreshFilesAction();
            // 默认打开可继续编辑的那份（.drawio → 内嵌 draw.io）。SVG 是母版、能看能打印，
            // 但打开即到头；律师拿到图后的下一个动作多半是"这里挪一下、那个字改一下"，
            // 落在只读预览上就得先自己去文件树里找可编辑版。没出 .drawio 时退回 SVG。
            ProjectFile openTarget = drawio != null ? drawio : svg;
            if (openTarget != null) editorBridgeService.sendOpenFileAction(openTarget);

            String note = buildDeliveryNote(r.raw(), folder, registered, draft);
            if (st != null) {
                st.lastRenderFingerprint = renderFingerprint;
                st.lastRenderNote = note;
                // 这一轮的确认已经消费掉了：下一张图要重新走一次三问。
                st.pendingCheckpointFingerprint = null;
                st.pendingCheckpointQuestions = null;
                st.checkpointCalls = 0;
            }
            return note;

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
        boolean editable = files.stream().anyMatch(n -> n.endsWith(".drawio"));
        sb.append(editable
                ? "其中 .drawio 是可以接着改的源文件，已在编辑器里打开（应用内嵌了 draw.io，"
                        + "也可以用 draw.io、ProcessOn 打开）；.svg 是母版，看和打印用。\n"
                : "其中 .svg 是母版（已在编辑器打开）。\n");
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
        if (!draft) {
            // 出图是这条链的终点。不写死这一句的话，真机上出现过"渲染完又渲染一次"。
            // 语义地图那句是给 write_file 冲动的另一道闸：它已经随图落盘了。
            sb.append("\n[本图已交付] 不要再调用 litigation_render（除非用户要求改内容或换风格）。"
                    + "语义地图已随图存为 ").append(folder.getName()).append(".map.json，"
                    + "不需要你另外用 write_file 保存一份。现在把上面的交付说明讲给用户即可。\n");
        }
        return sb.toString();
    }

    /** 同名文件夹已存在时复用而不是报错——同一张图重出一版是常态。 */
    ProjectFile createFolderTolerant(Long projectId, Long parentId, String name) {
        // 先按 createFolder 的口径归一父节点（模型常把「根目录」写成 0，见 dev-board#457）：
        // 不归一的话，下面那次兜底查同级会拿 parentId=0 去查，永远查空，
        // 于是「同名已存在」这条本该复用的路径变成硬失败「出图失败」。
        parentId = projectFileService.resolveParentId(projectId, parentId);
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
    ProjectFile registerArtifact(Long projectId, Long folderId, Path src, String marker) {
        try {
            String name = src.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            ProjectFile pf = projectFileService.createFile(
                    projectId, folderId, name, ext, Files.size(src), null,
                    newMarker(marker, projectId), AGENT_USER_ID);
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
    static String sanitize(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) return fallback;
        return s.length() > 60 ? s.substring(0, 60).trim() : s;
    }

    static String tail(String s, int max) {
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
