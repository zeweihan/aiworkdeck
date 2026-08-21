package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.WorkSessionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 纯文本/代码文件（txt/md/markdown/json/js/html/css/yml 等）的直读直写原语。
 *
 * <p>背景：这类文件自 dev-board#37 起不再进 LOWA 编辑器（前端改走 CodeMirror 轻量
 * 文本编辑器），doc_* 那条「SSE 下发 → 编辑器执行 → 回执」的桥对它们不再适用。
 * dev-board#61 起插件开发形态把代码文件（js/json/html/css 等）也纳入这条轻量路径。
 * 这里走后端直改：StorageService 读写字节 + WorkSessionService.onChangeSignal 接上
 * 版本记录（与 FileController.uploadFile 同一信号），改完发单向 SSE
 * {@code text_reload_file} 让前端已打开的文本标签就地重载。
 *
 * <p>纯后端执行、无客户端执行器依赖，因此不参与 ClientCapabilityService 的
 * 能力过滤（与 extract_file_text 同口径）；text_ 前缀仅作命名区隔。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TextFileEditTools implements AgentToolComponent {

    private final ProjectFileRepository projectFileRepository;
    private final StorageServiceFactory storageServiceFactory;
    private final WorkSessionService workSessionService;
    private final EditorBridgeService editorBridgeService;
    private final com.checkba.service.UserService userService;

    /** 与前端 fileOpenTabs.js 的 PLAIN_TEXT_TYPES 对齐：只有这几种进轻量文本编辑器。 */
    static final Set<String> PLAIN_TEXT_TYPES = Set.of(
            "txt", "md", "markdown", "json", "js", "mjs", "css", "html", "htm", "yml", "yaml");

    /** 大文件熔断：纯文本超过这个尺寸基本是日志/导出物，整篇改写没有意义还吃内存。 */
    private static final long MAX_TEXT_BYTES = 5L * 1024 * 1024;

    @ToolMeta(displayName = "写入文本文件", category = "file", fileEffect = "MODIFIED")
    @Tool("整篇覆盖写入一个纯文本/代码文件（txt/md/json/js/html/css/yml 等，UTF-8）。docx/xlsx/pptx 等 Office 文档"
            + "禁止用本工具，那些走 doc_* / sheet_* / slide_* 编辑原语。读取纯文本用 extract_file_text。"
            + "写入即生效（纯文本没有修订机制），并自动进入版本记录、同步刷新用户已打开的文本标签。")
    public String text_write_file(
            @P("项目文件数据库 ID（从 list_files / extract_file_text 等处获取）") Long fileId,
            @P("新的完整文件内容（整篇覆盖）") String content
    ) {
        log.info("Tool: text_write_file called for fileId={}", fileId);
        Resolved r = resolveTextFile(fileId);
        if (r.error != null) return r.error;
        ProjectFile pf = r.file;
        String text = content == null ? "" : content;
        try {
            writeBack(pf, text);
            return "已写入 " + pf.getName() + "（" + text.length() + " 字符）。改动已进入版本记录。";
        } catch (Exception e) {
            log.warn("text_write_file failed for fileId={}", fileId, e);
            return "Error: 写入失败: " + e.getMessage();
        }
    }

    /**
     * 「一次都没命中」的返回串。带 {@link ToolRegistry.ToolResult#UNCHANGED_PREFIX}
     * 前缀，好让编排器的副作用层知道这一次不该发 file_change——本工具声明的
     * {@code fileEffect="MODIFIED"} 是常量，不带这个前缀，一次没命中的查找也会被
     * 记成「本轮修改了这个文件」。
     */
    public static String noHitMessage(String find, int length) {
        return com.checkba.service.ai.ToolRegistry.ToolResult.UNCHANGED_PREFIX
                + "未找到 \"" + abbreviate(find) + "\"（文件共 " + length + " 字符）。";
    }

    @ToolMeta(displayName = "文本查找替换", category = "file", fileEffect = "MODIFIED")
    @Tool("在纯文本/代码文件（txt/md/json/js/html/css/yml 等）中做字面量查找替换（非正则）。"
            + "replaceAll=true 替换全部命中，false 只替换第一处；返回命中次数。"
            + "docx 等 Office 文档禁止用本工具（用 doc_find_replace）。改动直接生效并进入版本记录。")
    public String text_find_replace(
            @P("项目文件数据库 ID") Long fileId,
            @P("要查找的文本（字面量，区分大小写）") String find,
            @P("替换为的文本") String replace,
            @P("是否替换全部命中（false 只替换第一处）") boolean replaceAll
    ) {
        log.info("Tool: text_find_replace called for fileId={}", fileId);
        if (find == null || find.isEmpty()) {
            return "Error: find 不能为空。";
        }
        Resolved r = resolveTextFile(fileId);
        if (r.error != null) return r.error;
        ProjectFile pf = r.file;
        try {
            String text = readText(pf);
            int hits = countOccurrences(text, find);
            if (hits == 0) {
                return noHitMessage(find, text.length());
            }
            String replacement = replace == null ? "" : replace;
            String updated = replaceAll
                    ? text.replace(find, replacement)
                    : text.replaceFirst(java.util.regex.Pattern.quote(find),
                            java.util.regex.Matcher.quoteReplacement(replacement));
            int applied = replaceAll ? hits : 1;
            writeBack(pf, updated);
            return "已在 " + pf.getName() + " 中替换 " + applied + " 处（命中 " + hits + " 处）。改动已进入版本记录。";
        } catch (Exception e) {
            log.warn("text_find_replace failed for fileId={}", fileId, e);
            return "Error: 替换失败: " + e.getMessage();
        }
    }

    // ==================== 内部实现 ====================

    /** 定位结果：file 与 error 恰有其一（工具是单例 Bean，不能把失败原因存实例字段）。 */
    private record Resolved(ProjectFile file, String error) {}

    /** 定位并校验目标：存在、同项目、确为纯文本扩展名。 */
    private Resolved resolveTextFile(Long fileId) {
        if (fileId == null) return new Resolved(null, "Error: fileId is required.");
        Optional<ProjectFile> opt = projectFileRepository.findById(fileId);
        if (opt.isEmpty()) {
            return new Resolved(null, "Error: 文件不存在，ID=" + fileId);
        }
        ProjectFile pf = opt.get();
        String denied = ToolFileGuard.rejectIfOutsideProject(pf);
        if (denied != null) return new Resolved(null, denied);
        if (Boolean.TRUE.equals(pf.getIsFolder())) {
            return new Resolved(null, "Error: ID=" + fileId + " 是文件夹，不是文本文件。");
        }
        if (!isPlainText(pf)) {
            return new Resolved(null, "Error: " + pf.getName() + " 不是纯文本文件。本工具仅限纯文本/代码文件"
                    + "（txt/md/json/js/html/css/yml 等）；Word/Excel/PPT 请分别用 doc_* / sheet_* / slide_* 编辑原语。");
        }
        return new Resolved(pf, null);
    }

    static boolean isPlainText(ProjectFile pf) {
        String ext = pf.getFileType();
        if (ext == null || ext.isBlank()) {
            String name = pf.getName();
            int dot = name == null ? -1 : name.lastIndexOf('.');
            ext = (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
        }
        return PLAIN_TEXT_TYPES.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** 存储键与 DocumentTextService.extractText 同口径：filePath 优先，回退 wpsFileId。 */
    private String storageKey(ProjectFile pf) {
        String path = pf.getFilePath();
        if (path == null || path.isBlank()) path = pf.getWpsFileId();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("文件没有存储路径: " + pf.getId());
        }
        return path;
    }

    /** 原始字节按 UTF-8 直读——不能走 Tika 抽取，改写回去要的是逐字节可逆的原文。 */
    private String readText(ProjectFile pf) throws Exception {
        Resource resource = storageServiceFactory.getStorageService().load(storageKey(pf));
        try (InputStream is = resource.getInputStream()) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalStateException("文件过大（>" + (MAX_TEXT_BYTES / 1024 / 1024) + "MB），拒绝整篇改写。");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 落盘 + 元数据 + 版本信号 + 前端刷新。
     * 与 FileController.uploadFile 的收尾同构：字节写成功后元数据/信号失败只记日志，
     * 不把已生效的写入报成失败。
     */
    private void writeBack(ProjectFile pf, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalStateException("内容过大（>" + (MAX_TEXT_BYTES / 1024 / 1024) + "MB），拒绝写入。");
        }
        storageServiceFactory.getStorageService().save(storageKey(pf), new ByteArrayInputStream(bytes));
        try {
            pf.setFileSize((long) bytes.length);
            pf.setUpdatedAt(LocalDateTime.now());
            projectFileRepository.save(pf);
        } catch (Exception e) {
            log.warn("text write: 回写文件元数据失败 fileId={}", pf.getId(), e);
        }
        signalChange(pf);
        try {
            editorBridgeService.sendTextReloadFileAction(pf);
        } catch (Exception e) {
            log.warn("text write: 发送 text_reload_file 失败 fileId={}", pf.getId(), e);
        }
    }

    /** 版本记录信号，与 FileController.uploadFile / ProjectFileService 同口径：失败只记日志。 */
    private void signalChange(ProjectFile pf) {
        try {
            Long userId = ProjectContextHolder.getUserId();
            workSessionService.onChangeSignal(pf.getProjectId(), userId, resolveUserName(userId));
        } catch (Exception e) {
            log.warn("发送版本变更信号失败: project={}", pf.getProjectId(), e);
        }
    }

    private String resolveUserName(Long userId) {
        if (userId != null) {
            try {
                var u = userService.getUserById(userId);
                if (u != null && u.getUsername() != null) return u.getUsername();
            } catch (Exception e) {
                log.warn("解析用户名失败: userId={}", userId, e);
            }
        }
        // 拿不到会话用户就署 AI：这条路只有 AI 工具会走，比泛称「用户」更如实
        return "AI WorkDeck";
    }

    static int countOccurrences(String text, String find) {
        if (text == null || find == null || find.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(find, idx)) >= 0) {
            count++;
            idx += find.length();
        }
        return count;
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
