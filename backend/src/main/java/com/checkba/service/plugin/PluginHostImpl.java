package com.checkba.service.plugin;

import com.checkba.model.entity.PluginJob;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.plugin.api.ConflictPolicy;
import com.checkba.plugin.api.Docs;
import com.checkba.plugin.api.Evidence;
import com.checkba.plugin.api.FileInfo;
import com.checkba.plugin.api.Files;
import com.checkba.plugin.api.JobBody;
import com.checkba.plugin.api.JobHandle;
import com.checkba.plugin.api.JobStatus;
import com.checkba.plugin.api.Jobs;
import com.checkba.plugin.api.LinkView;
import com.checkba.plugin.api.Llm;
import com.checkba.plugin.api.LlmOptions;
import com.checkba.plugin.api.OcrBlock;
import com.checkba.plugin.api.OcrOptions;
import com.checkba.plugin.api.OcrResult;
import com.checkba.plugin.api.PluginHost;
import com.checkba.plugin.api.Settings;
import com.checkba.plugin.api.TagInfo;
import com.checkba.plugin.api.Tags;
import com.checkba.plugin.api.TargetInput;
import com.checkba.plugin.api.TargetView;
import com.checkba.plugin.api.Text;
import com.checkba.plugin.api.ToolCall;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.PlatformAiUserScope;
import com.checkba.service.evidence.EvidenceLinkViews;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link PluginHost} 的宿主实现（插件规范 v2.4 §11）。按插件 id 绑定；每个方法先过配额再校项目成员权限。
 *
 * <p>调用上下文来自 {@link PluginHostFactory#currentCall()}：工具分发期由 ToolRegistry 绑定，
 * 后台任务期由 {@link Jobs#start} 的任务体包装绑定快照。两者都没有时一律拒绝——
 * 插件不能在没有用户身份的线程上读写项目。
 */
@Slf4j
class PluginHostImpl implements PluginHost {

    /**
     * Docs.exec 放行的编辑器原语 = AI 工具已暴露的 doc_ / sheet_ / slide_ 下发名 ∪ EvidenceLink 书签/链接原语
     * （clear_anchors / insert_paragraph / bookmark_selection / get_bookmark_context / goto_bookmark /
     * check_link_anchors / get_selection_hyperlink / set_selection_hyperlink / insert_link_with_bookmark——
     * 尽调插件在文档里建锚点必需，2026-08-22 复核裁决保留）。宿主自用（load/export_document、
     * doc_open_file_sync、set_zoom 等）与诊断原语不开放。改这张表要同步 docs/PLUGIN_SPEC.md §11 的 Docs.exec 行。
     */
    static final Set<String> DOC_ACTIONS = Set.of(
            // writer
            "insert_at_cursor", "replace_selection", "find_replace", "get_selection", "find_text_locations",
            "replace_nth_match", "delete_match", "delete_text", "get_paragraph", "modify_paragraph", "get_outline",
            "goto", "set_selection", "replace_at_position", "clear_anchors", "get_document_text", "get_cursor_context",
            "get_clauses", "select_paragraph", "collapse_selection", "delete_selection", "format_selection",
            "set_paragraph_format", "undo", "redo", "insert_paragraph", "insert_table", "insert_break", "insert_image",
            "insert_under_heading", "format_table", "get_formatting", "set_style", "set_numbering", "edit_header_footer",
            "apply_house_style", "add_comment", "list_comments", "reply_comment", "set_comment_resolved", "delete_comment",
            "list_revisions", "resolve_revision", "resolve_all_revisions", "set_hyperlink_at_anchor",
            "bookmark_selection", "get_bookmark_context", "goto_bookmark", "check_link_anchors",
            "get_selection_hyperlink", "set_selection_hyperlink", "insert_link_with_bookmark",
            // calc
            "sheet_get_overview", "sheet_read_range", "sheet_write_cells", "sheet_format_cells", "sheet_set_borders",
            "sheet_merge_cells", "sheet_set_row_col", "sheet_edit_rows_cols", "sheet_manage_sheets", "sheet_search",
            "sheet_select_range", "sheet_sort_range", "sheet_set_autofilter", "sheet_freeze_panes",
            "sheet_conditional_format", "sheet_set_data_validation", "sheet_define_name", "sheet_group_rows_cols",
            "sheet_protect_sheet", "sheet_add_chart", "sheet_add_pivot_table", "sheet_add_comment",
            "sheet_get_comments", "sheet_delete_comment",
            // impress
            "slide_get_overview", "slide_get_page", "slide_goto", "slide_add_page", "slide_delete_page",
            "slide_move_page", "slide_add_text_box", "slide_add_shape", "slide_add_table", "slide_delete_shape",
            "slide_format_shape", "slide_format_text");

    static final Set<String> IMAGE_TYPES = Set.of("png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff");
    static final int OCR_MAX_PAGES = 50;
    static final String STYLE_PROFILE_SETTING = "dd.styleProfile.default";
    static final String STYLE_PROFILE_RESOURCE = "style-profiles/house-default.json";
    static final String TEMPLATE_FOLDER = "_模板";
    static final String TEMPLATE_PROFILE_FILE = "画像.json";

    private final String pluginId;
    private final PluginHostFactory f;

    private final Files files = new FilesImpl();
    private final Text text = new TextImpl();
    private final Tags tags = new TagsImpl();
    private final Evidence evidence = new EvidenceImpl();
    private final Jobs jobs = new JobsImpl();
    private final Docs docs = new DocsImpl();
    private final Settings settings = new SettingsImpl();
    private final Llm llm = new LlmImpl();

    PluginHostImpl(String pluginId, PluginHostFactory factory) {
        this.pluginId = pluginId;
        this.f = factory;
    }

    @Override public String pluginId() { return pluginId; }
    @Override public ToolCall call() { return f.currentCall(); }
    @Override public Files files() { return files; }
    @Override public Text text() { return text; }
    @Override public Tags tags() { return tags; }
    @Override public Evidence evidence() { return evidence; }
    @Override public Jobs jobs() { return jobs; }
    @Override public Docs docs() { return docs; }
    @Override public Settings settings() { return settings; }
    @Override public Llm llm() { return llm; }

    // ------------------------------------------------------------------ guards

    /** 配额 + 必须有调用上下文。 */
    private ToolCall enter() {
        f.quota.acquire(pluginId);
        ToolCall c = f.currentCall();
        if (c == null || c.userId() == null) {
            throw new IllegalStateException(LangText.of(
                    "插件 " + pluginId + " 在没有调用上下文的线程上访问宿主",
                    "Plugin " + pluginId + " called the host without a call context"));
        }
        return c;
    }

    private ToolCall requireRead(long projectId) {
        ToolCall c = enter();
        if (!f.projectMemberService.hasReadPermission(projectId, c.userId())) {
            throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project"));
        }
        return c;
    }

    private ToolCall requireWrite(long projectId) {
        ToolCall c = enter();
        if (!f.projectMemberService.hasWritePermission(projectId, c.userId())) {
            throw new IllegalArgumentException(LangText.of("无权限修改该项目", "No write access to this project"));
        }
        return c;
    }

    private ProjectFile requireProjectFile(long projectId, long fileId) {
        ProjectFile pf = f.projectFileRepository.findById(fileId).orElse(null);
        if (pf == null || !Long.valueOf(projectId).equals(pf.getProjectId()) || Boolean.TRUE.equals(pf.getIsDeleted())) {
            throw new IllegalArgumentException(LangText.of("文件不存在: ", "File not found: ") + fileId);
        }
        return pf;
    }

    private byte[] readBytes(ProjectFile pf) {
        if (Boolean.TRUE.equals(pf.getIsFolder()) || !StringUtils.hasText(pf.getFilePath())) {
            throw new IllegalArgumentException(LangText.of("不是可读文件: ", "Not a readable file: ") + pf.getName());
        }
        try (InputStream in = f.storageServiceFactory.getStorageService().load(pf.getFilePath()).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 把存储里的文件流式落到临时文件（不整份驻留堆），调用方用完 {@link #deleteQuietly}。 */
    private Path copyToTemp(ProjectFile pf) {
        if (Boolean.TRUE.equals(pf.getIsFolder()) || !StringUtils.hasText(pf.getFilePath())) {
            throw new IllegalArgumentException(LangText.of("不是可读文件: ", "Not a readable file: ") + pf.getName());
        }
        try {
            Path tmp = java.nio.file.Files.createTempFile("plugin-host-", "." + ext(pf.getName()));
            try (InputStream in = f.storageServiceFactory.getStorageService().load(pf.getFilePath()).getInputStream()) {
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p != null) java.nio.file.Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响结果
        }
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private Map<String, Object> readMeta(ProjectFile pf) {
        if (!StringUtils.hasText(pf.getMetaJson())) return new LinkedHashMap<>();
        try {
            return f.objectMapper.readValue(pf.getMetaJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeMeta(ProjectFile pf, Map<String, Object> meta) {
        try {
            pf.setMetaJson(f.objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        f.projectFileRepository.save(pf);
    }

    // ------------------------------------------------------------------ Files

    private final class FilesImpl implements Files {

        private Map<Long, ProjectFile> index(long projectId) {
            Map<Long, ProjectFile> m = new HashMap<>();
            for (ProjectFile p : f.projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId)) {
                m.put(p.getId(), p);
            }
            return m;
        }

        private String pathOf(ProjectFile p, Map<Long, ProjectFile> idx) {
            StringBuilder sb = new StringBuilder(p.getName());
            Long parent = p.getParentId();
            int guard = 0;
            while (parent != null && guard++ < 64) {
                ProjectFile pp = idx.get(parent);
                if (pp == null) break;
                sb.insert(0, pp.getName() + "/");
                parent = pp.getParentId();
            }
            return sb.toString();
        }

        private FileInfo info(ProjectFile p, Map<Long, ProjectFile> idx) {
            Map<String, Object> meta = readMeta(p);
            Object sha = meta.get("sha256");
            return new FileInfo(p.getId(), p.getName(), p.getParentId(), Boolean.TRUE.equals(p.getIsFolder()),
                    p.getFileType(), p.getFileSize() == null ? 0L : p.getFileSize(), pathOf(p, idx),
                    sha instanceof String s ? s : null, p.getMetaJson());
        }

        private boolean under(ProjectFile p, Long ancestorId, Map<Long, ProjectFile> idx) {
            if (ancestorId == null) return true;
            Long parent = p.getParentId();
            int guard = 0;
            while (parent != null && guard++ < 64) {
                if (parent.equals(ancestorId)) return true;
                ProjectFile pp = idx.get(parent);
                if (pp == null) return false;
                parent = pp.getParentId();
            }
            return false;
        }

        @Override
        public List<FileInfo> list(long projectId, Long parentId, boolean recursive) {
            requireRead(projectId);
            Map<Long, ProjectFile> idx = index(projectId);
            List<FileInfo> out = new ArrayList<>();
            for (ProjectFile p : idx.values()) {
                boolean hit = recursive ? under(p, parentId, idx) : java.util.Objects.equals(p.getParentId(), parentId);
                if (hit) out.add(info(p, idx));
            }
            out.sort(java.util.Comparator.comparing(FileInfo::path));
            return out;
        }

        @Override
        public FileInfo get(long projectId, long fileId) {
            requireRead(projectId);
            ProjectFile p = requireProjectFile(projectId, fileId);
            return info(p, index(projectId));
        }

        @Override
        public InputStream open(long projectId, long fileId) {
            requireRead(projectId);
            return new ByteArrayInputStream(readBytes(requireProjectFile(projectId, fileId)));
        }

        @Override
        public FileInfo createFolderPath(long projectId, List<String> segments) {
            ToolCall c = requireWrite(projectId);
            ProjectFile folder = f.projectFileService.ensureFolderPath(projectId, c.userId(), segments);
            return info(folder, index(projectId));
        }

        @Override
        public FileInfo write(long projectId, Long parentId, String name, InputStream bytes, ConflictPolicy policy) {
            ToolCall c = requireWrite(projectId);
            if (!StringUtils.hasText(name)) throw new IllegalArgumentException("name required");
            if (parentId != null) {
                // 与 move 同款：父节点必须是本项目的文件夹（不然能把文件挂到别的项目 / 挂到文件底下）
                ProjectFile parent = requireProjectFile(projectId, parentId);
                if (!Boolean.TRUE.equals(parent.getIsFolder())) {
                    throw new IllegalArgumentException(LangText.of("目标不是文件夹", "Target is not a folder"));
                }
            }
            byte[] data;
            try {
                data = bytes == null ? new byte[0] : bytes.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ProjectFileService.ConflictPolicy p = policy == ConflictPolicy.RENAME
                    ? ProjectFileService.ConflictPolicy.RENAME : ProjectFileService.ConflictPolicy.FAIL;
            String type = ext(name);
            ProjectFile created = f.projectFileService.createFile(projectId, parentId, name.trim(),
                    type.isEmpty() ? "bin" : type, (long) data.length, null, null, c.userId(), p);
            try {
                f.storageServiceFactory.getStorageService().save(created.getFilePath(), new ByteArrayInputStream(data));
            } catch (Exception e) {
                throw new IllegalStateException(LangText.of("写入文件失败: ", "Failed to write file: ") + e.getMessage(), e);
            }
            return info(created, index(projectId));
        }

        @Override
        public FileInfo move(long projectId, long fileId, Long newParentId) {
            ToolCall c = requireWrite(projectId);
            requireProjectFile(projectId, fileId);
            if (newParentId != null) {
                ProjectFile target = requireProjectFile(projectId, newParentId);
                if (!Boolean.TRUE.equals(target.getIsFolder())) {
                    throw new IllegalArgumentException(LangText.of("目标不是文件夹", "Target is not a folder"));
                }
            }
            ProjectFile moved = f.projectFileService.move(fileId, newParentId, null, c.userId());
            return info(moved, index(projectId));
        }

        @Override
        public FileInfo rename(long projectId, long fileId, String newName) {
            ToolCall c = requireWrite(projectId);
            requireProjectFile(projectId, fileId);
            ProjectFile renamed = f.projectFileService.rename(fileId, newName, c.userId());
            return info(renamed, index(projectId));
        }

        @Override
        public void setMeta(long projectId, long fileId, Map<String, Object> metaPatch) {
            requireWrite(projectId);
            ProjectFile p = requireProjectFile(projectId, fileId);
            if (metaPatch == null || metaPatch.isEmpty()) return;
            Map<String, Object> meta = readMeta(p);
            for (Map.Entry<String, Object> e : metaPatch.entrySet()) {
                if (e.getValue() == null) meta.remove(e.getKey()); else meta.put(e.getKey(), e.getValue());
            }
            writeMeta(p, meta);
        }

        @Override
        public String sha256(long projectId, long fileId) {
            requireRead(projectId);
            ProjectFile p = requireProjectFile(projectId, fileId);
            Map<String, Object> meta = readMeta(p);
            String stamp = p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString();
            if (meta.get("sha256") instanceof String cached && stamp.equals(meta.get("sha256At"))) {
                return cached;
            }
            byte[] data = readBytes(p);
            String hex;
            try {
                hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            meta.put("sha256", hex);
            meta.put("sha256At", stamp);
            try {
                writeMeta(p, meta);
            } catch (Exception e) {
                log.debug("sha256 cache write skipped for file {}: {}", fileId, e.getMessage());
            }
            return hex;
        }
    }

    // ------------------------------------------------------------------ Text

    private final class TextImpl implements Text {

        @Override
        public String extract(long projectId, long fileId, int maxChars) {
            requireRead(projectId);
            ProjectFile p = requireProjectFile(projectId, fileId);
            String s;
            try {
                s = f.documentTextService.extractText(p);
            } catch (Exception e) {
                throw new IllegalStateException(LangText.of("文本抽取失败: ", "Text extraction failed: ") + e.getMessage(), e);
            }
            if (s == null) return "";
            return maxChars > 0 && s.length() > maxChars ? s.substring(0, maxChars) : s;
        }

        @Override
        public OcrResult ocr(long projectId, long fileId, OcrOptions o) {
            ToolCall c = requireRead(projectId);
            ProjectFile p = requireProjectFile(projectId, fileId);
            OcrOptions opt = o == null ? OcrOptions.text() : o;
            String type = StringUtils.hasText(p.getFileType()) ? p.getFileType().toLowerCase() : ext(p.getName());
            StringBuilder all = new StringBuilder();
            List<OcrBlock> blocks = new ArrayList<>();
            if ("pdf".equals(type)) {
                // 逐页 render → OCR → 丢弃：50 页 base64 攒成 List 会把几百 MB 驻在堆里；
                // 文件本体也不整份 readAllBytes，先落临时文件让 PDFBox 按需读
                Path tmp = copyToTemp(p);
                try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
                    PDFRenderer renderer = new PDFRenderer(doc);
                    int pages = Math.min(doc.getNumberOfPages(), OCR_MAX_PAGES);
                    for (int i = 0; i < pages; i++) {
                        BufferedImage img = renderer.renderImageWithDPI(i, 150);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ImageIO.write(img, "png", bos);
                        appendPage(all, blocks, opt, i + 1, ocrOne(c, Base64.getEncoder().encodeToString(bos.toByteArray())));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    deleteQuietly(tmp);
                }
            } else if (IMAGE_TYPES.contains(type)) {
                appendPage(all, blocks, opt, 1, ocrOne(c, Base64.getEncoder().encodeToString(readBytes(p))));
            } else {
                throw new IllegalArgumentException(LangText.of(
                        "OCR 只支持图片与 PDF: ", "OCR supports images and PDF only: ") + p.getName());
            }
            return new OcrResult(all.toString(), blocks);
        }

        /** 平台网关按用户计费：在发起用户的作用域里调。 */
        private String ocrOne(ToolCall c, String b64) {
            com.checkba.service.ocr.OcrResult r = PlatformAiUserScope.call(c.userId(), () -> f.ocrService.recognizeGeneral(b64));
            return r == null || r.getText() == null ? "" : r.getText();
        }

        private void appendPage(StringBuilder all, List<OcrBlock> blocks, OcrOptions opt, int page, String t) {
            if (all.length() > 0) all.append('\n');
            all.append(t);
            if (opt.blocks()) {
                // 网关不回坐标，块粒度到页：一页一块、整页矩形
                blocks.add(new OcrBlock(t, page, 0, 0, 1, 1));
            }
        }

        @Override
        public List<String> pdfPageTexts(long projectId, long fileId, int fromPage, int toPage) {
            requireRead(projectId);
            ProjectFile p = requireProjectFile(projectId, fileId);
            List<String> out = new ArrayList<>();
            Path tmp = copyToTemp(p);
            try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
                int n = doc.getNumberOfPages();
                int from = Math.max(1, fromPage);
                int to = toPage <= 0 ? n : Math.min(n, toPage);
                PDFTextStripper stripper = new PDFTextStripper();
                for (int pg = from; pg <= to; pg++) {
                    stripper.setStartPage(pg);
                    stripper.setEndPage(pg);
                    out.add(stripper.getText(doc));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                deleteQuietly(tmp);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ Tags

    private final class TagsImpl implements Tags {

        private TagInfo info(Tag t) {
            return new TagInfo(t.getId(), t.getName(), t.getType() == null ? "NORMAL" : t.getType(), t.getColor());
        }

        @Override
        public TagInfo getOrCreate(long projectId, String name, String type) {
            requireWrite(projectId);
            if (!StringUtils.hasText(name)) throw new IllegalArgumentException("name required");
            Tag t = f.tagService.getOrCreateTag(projectId, name.trim(), StringUtils.hasText(type) ? type : "NORMAL", null);
            return info(t);
        }

        @Override
        public void tagFile(long projectId, long fileId, long tagId) {
            ToolCall c = requireWrite(projectId);
            requireProjectFile(projectId, fileId);
            Optional<Tag> t = f.tagRepository.findById(tagId);
            if (t.isEmpty() || !Long.valueOf(projectId).equals(t.get().getProjectId())) {
                throw new IllegalArgumentException(LangText.of("标签不存在: ", "Tag not found: ") + tagId);
            }
            f.fileTagService.addTagToFile(fileId, tagId, c.userId());
        }

        @Override
        public List<TagInfo> tagsOf(long projectId, long fileId) {
            requireRead(projectId);
            requireProjectFile(projectId, fileId);
            return f.fileTagService.getTagsByFileId(fileId).stream().map(this::info).toList();
        }
    }

    // ------------------------------------------------------------------ Evidence

    private final class EvidenceImpl implements Evidence {

        private List<EvidenceLinkViews.TargetInput> in(List<TargetInput> targets) {
            if (targets == null) return List.of();
            return targets.stream().map(t -> new EvidenceLinkViews.TargetInput(
                    t.fileId(), t.locatorJson(), t.relation(), t.method(), t.confidence(), t.note())).toList();
        }

        private LinkView out(EvidenceLinkViews.LinkView v) {
            List<TargetView> ts = v.targets() == null ? List.of() : v.targets().stream().map(t -> new TargetView(
                    t.id() == null ? 0 : t.id(), t.fileId() == null ? 0 : t.fileId(),
                    t.file() == null ? null : t.file().name(),
                    t.locator() == null || t.locator().isNull() ? null : t.locator().toString(),
                    t.relation(), t.method())).toList();
            return new LinkView(v.id() == null ? 0 : v.id(), v.linkKey(), v.docFileId() == null ? 0 : v.docFileId(),
                    v.anchorText(), v.sectionPath(), v.sectionTitle(), v.status(), ts);
        }

        @Override
        public LinkView create(long projectId, long docFileId, String linkKey, String anchorText, String sectionPath,
                               String sectionTitle, List<TargetInput> targets) {
            ToolCall c = requireWrite(projectId);
            return out(f.evidenceLinkService.create(c.userId(), projectId, docFileId, linkKey, anchorText,
                    sectionPath, sectionTitle, com.checkba.model.entity.EvidenceLink.KIND_PLUGIN, in(targets)));
        }

        @Override
        public LinkView addTargets(long projectId, String linkKey, List<TargetInput> targets) {
            ToolCall c = requireWrite(projectId);
            return out(f.evidenceLinkService.addTargets(c.userId(), projectId, linkKey, in(targets),
                    com.checkba.model.entity.EvidenceLink.KIND_PLUGIN));
        }

        @Override
        public List<LinkView> listByDoc(long projectId, long docFileId) {
            ToolCall c = requireRead(projectId);
            return f.evidenceLinkService.listByDoc(c.userId(), projectId, docFileId, null, null)
                    .stream().map(this::out).toList();
        }

        @Override
        public List<LinkView> listByFile(long projectId, long fileId) {
            ToolCall c = requireRead(projectId);
            return f.evidenceLinkService.listByFile(c.userId(), projectId, fileId).stream().map(this::out).toList();
        }
    }

    // ------------------------------------------------------------------ Jobs

    private final class JobsImpl implements Jobs {

        @Override
        public JobHandle start(String kind, String title, JobBody body) {
            ToolCall c = enter();
            if (c.projectId() != null && !f.projectMemberService.hasWritePermission(c.projectId(), c.userId())) {
                throw new IllegalArgumentException(LangText.of("无权限修改该项目", "No write access to this project"));
            }
            ToolCall snapshot = c;
            // 任务线程上也要有调用上下文：任务体里的 host.files()/llm() 才能鉴权与计费
            JobBody wrapped = ctx -> {
                f.bindCall(snapshot);
                try {
                    body.run(ctx);
                } finally {
                    f.clear();
                }
            };
            return f.pluginJobService.start(pluginId, kind, title, snapshot, wrapped);
        }

        /** 只认本插件的任务；任务挂在项目上时，还要当前用户对那个项目有读（status）/ 写（cancel）权限。 */
        private PluginJob ownJob(String jobId, boolean write) {
            ToolCall c = enter();
            PluginJob j = f.pluginJobService.get(jobId);
            if (j == null || !pluginId.equals(j.getPluginId())) return null;
            if (j.getProjectId() != null) {
                boolean ok = write ? f.projectMemberService.hasWritePermission(j.getProjectId(), c.userId())
                        : f.projectMemberService.hasReadPermission(j.getProjectId(), c.userId());
                if (!ok) {
                    throw new IllegalArgumentException(write
                            ? LangText.of("无权限修改该项目", "No write access to this project")
                            : LangText.of("无权限访问该项目", "No access to this project"));
                }
            }
            return j;
        }

        @Override
        public JobStatus status(String jobId) {
            return ownJob(jobId, false) == null ? null : f.pluginJobService.status(jobId);
        }

        @Override
        public void cancel(String jobId) {
            if (ownJob(jobId, true) == null) return;
            f.pluginJobService.cancel(jobId);
        }
    }

    // ------------------------------------------------------------------ Docs

    private final class DocsImpl implements Docs {

        private String conversation() {
            ToolCall c = enter();
            if (!StringUtils.hasText(c.conversationId())) {
                throw new IllegalStateException("no active conversation");
            }
            return c.conversationId();
        }

        /** EditorBridgeService 按自己的 ThreadLocal 找会话；后台任务线程上没有，这里按调用上下文临时绑定。 */
        private <T> T withConversation(String conversationId, java.util.function.Supplier<T> body) {
            String previous = f.editorBridgeService.getCurrentConversationId();
            f.editorBridgeService.setCurrentConversationId(conversationId);
            try {
                return body.get();
            } finally {
                if (previous == null) f.editorBridgeService.clearCurrentConversationId();
                else f.editorBridgeService.setCurrentConversationId(previous);
            }
        }

        @Override
        public String exec(String action, Map<String, Object> params) {
            String conv = conversation();
            if (action == null || !DOC_ACTIONS.contains(action)) {
                throw new IllegalArgumentException(LangText.of(
                        "编辑器原语不在白名单内: ", "Editor action not allowed: ") + action);
            }
            return withConversation(conv, () -> f.editorBridgeService.executeEditorCommand(action, params == null ? Map.of() : params));
        }

        @Override
        public void refreshFiles() {
            String conv = conversation();
            withConversation(conv, () -> {
                f.editorBridgeService.sendRefreshFilesAction();
                return null;
            });
        }

        @Override
        public void openFile(long fileId, Map<String, Object> locator) {
            ToolCall c = enter();
            if (!StringUtils.hasText(c.conversationId())) throw new IllegalStateException("no active conversation");
            if (c.projectId() == null) throw new IllegalStateException("no project context");
            requireRead(c.projectId());
            ProjectFile p = requireProjectFile(c.projectId(), fileId);
            withConversation(c.conversationId(), () -> {
                f.editorBridgeService.sendOpenFileAction(p);
                if (locator != null && !locator.isEmpty()) {
                    Map<String, Object> fields = new HashMap<>();
                    fields.put("fileId", p.getId());
                    fields.put("locator", locator);
                    f.editorBridgeService.sendClientAction("plugin_open_locator", c.conversationId(), fields);
                }
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ Settings

    private final class SettingsImpl implements Settings {

        private String key(String k) {
            if (!StringUtils.hasText(k)) throw new IllegalArgumentException("key required");
            return "plugin." + pluginId + "." + k.trim();
        }

        @Override
        public String get(String key) {
            enter();
            return f.systemSettingService.get(key(key), null);
        }

        @Override
        public void set(String key, String value) {
            enter();
            f.systemSettingService.set(key(key), value);
        }

        /**
         * 解析顺序（SPEC §3.4，去掉「工具显式传参」那一级）：项目 `_模板/画像.json` >
         * SystemSetting dd.styleProfile.default > classpath house-default.json > null。
         * 单元 I 合并后这里改调 StyleProfiles.resolveForProject，顺序不变。
         */
        @Override
        public String projectStyleProfileJson(long projectId) {
            requireRead(projectId);
            String fromProject = projectTemplateProfile(projectId);
            if (StringUtils.hasText(fromProject)) return fromProject;
            String fromSetting = f.systemSettingService.get(STYLE_PROFILE_SETTING, null);
            if (StringUtils.hasText(fromSetting)) return fromSetting;
            try (InputStream in = PluginHostImpl.class.getClassLoader().getResourceAsStream(STYLE_PROFILE_RESOURCE)) {
                if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.debug("house-default style profile unreadable: {}", e.getMessage());
            }
            return null;
        }

        private String projectTemplateProfile(long projectId) {
            try {
                Optional<ProjectFile> folder = f.projectFileRepository
                        .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, null, TEMPLATE_FOLDER);
                if (folder.isEmpty() || !Boolean.TRUE.equals(folder.get().getIsFolder())) return null;
                Optional<ProjectFile> file = f.projectFileRepository
                        .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, folder.get().getId(), TEMPLATE_PROFILE_FILE);
                if (file.isEmpty()) return null;
                String json = new String(readBytes(file.get()), StandardCharsets.UTF_8);
                JsonNode node = f.objectMapper.readTree(json);
                return node != null && node.isObject() ? json : null;
            } catch (Exception e) {
                log.debug("project template profile unreadable for project {}: {}", projectId, e.getMessage());
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ Llm

    private final class LlmImpl implements Llm {

        @Override
        public String complete(String systemPrompt, String userPrompt, LlmOptions o) {
            ToolCall c = enter();
            if (!StringUtils.hasText(userPrompt)) throw new IllegalArgumentException("userPrompt required");
            LlmOptions opt = o == null ? LlmOptions.cheap() : o;
            // 平台通道：modelId 为空走辅助模型（便宜档，与自动打标签同一条）；温度/最大 token 由通道侧模型配置决定
            String modelId = StringUtils.hasText(opt.modelId()) ? opt.modelId() : f.auxModelResolver.auxModelId();
            ChatLanguageModel model = StringUtils.hasText(opt.modelId())
                    ? f.chatModelFactory.getChatModel(opt.modelId()) : f.chatModelFactory.getAuxChatModel();
            List<ChatMessage> messages = new ArrayList<>();
            if (StringUtils.hasText(systemPrompt)) messages.add(SystemMessage.from(systemPrompt));
            messages.add(UserMessage.from(userPrompt));
            Response<AiMessage> r = PlatformAiUserScope.call(c.userId(), () -> model.generate(messages));
            try {
                if (r.tokenUsage() != null) {
                    f.tokenUsageService.recordUsage(c.projectId(), c.userId(), modelId, r.tokenUsage(), c.conversationId());
                }
            } catch (Exception e) {
                log.warn("plugin {} llm usage record failed: {}", pluginId, e.getMessage());
            }
            log.info("plugin {} llm.complete model={} tokens={}", pluginId, modelId, r.tokenUsage());
            return r.content() == null ? "" : r.content().text();
        }
    }
}
