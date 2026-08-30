package com.checkba.service.ai;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 插件开发形态（dev-board#61）：律师在项目文件树的「插件开发/&lt;id&gt;/」目录里
 * （亲手或经 AI）编写 Web 插件源码，本服务负责骨架生成、校验、装进本机
 * plugins/ 目录热重扫运行、以及卸载。
 *
 * <p>安全模型（与在线广场是两条不同的信任路径，勿混用）：
 * <ul>
 *   <li>广场安装 = 陌生代码：人工审核 + Ed25519 验签 + 装后默认禁用（PluginMarketService）。</li>
 *   <li>开发安装 = 本机用户自己写的代码：免签直装、装后即启用，但<b>只收纯 Web 插件</b>——
 *       manifest 里 backendJars / tools / skills / packs 任一非空一律拒装。JAR 与宿主同 JVM
 *       同权限，给它免审路径等于把签名闸变成摆设；Web 插件跑在 sandbox iframe +
 *       权限化桥（PluginPane / PluginWebController）里，才配得上「写完直接跑」。</li>
 * </ul>
 *
 * <p>开发安装的目录带 {@code .awd-dev} 标记文件：装机时据此区分来源，
 * 拒绝覆盖广场装的同名插件，卸载也只认带标记的目录。
 */
@Service
@Slf4j
public class PluginDevService {

    /** 项目根下的约定文件夹名，其直接子文件夹 = 一个插件源码项目（文件夹名即插件 id） */
    public static final String DEV_ROOT_FOLDER = "插件开发";

    /** 与 PluginMarketService / PluginWebController / NativePackService 同一套 id 规则 */
    static final Pattern PLUGIN_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    /** 开发安装的来源标记文件名（JSON：projectId / folderId / installedAt） */
    static final String DEV_MARKER = ".awd-dev";

    private static final Set<String> ALLOWED_PERMISSIONS = Set.of("file_read", "file_write", "network", "editor", "ai");
    private static final int MAX_FILES = 200;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final PluginService pluginService;
    private final String pluginsDir;

    /** 宿主版本（规范 v2.7 P0：minHostVersion 校验基准；dev 态 "dev" 非 semver 时跳过比较） */
    @Value("${telemetry.app-version:${AWD_APP_VERSION:dev}}")
    String appVersion = "dev";

    public PluginDevService(ProjectFileRepository projectFileRepository,
                            ProjectFileService projectFileService,
                            StorageServiceFactory storageServiceFactory,
                            PluginService pluginService,
                            @Value("${ai.plugins.dir:plugins}") String pluginsDir) {
        this.projectFileRepository = projectFileRepository;
        this.projectFileService = projectFileService;
        this.storageServiceFactory = storageServiceFactory;
        this.pluginService = pluginService;
        this.pluginsDir = pluginsDir;
    }

    /** 面板列表行：id 取自文件夹名（契约：manifest.id 必须与之一致，install 时强校验） */
    public record DevPluginStatus(String id, String name, String version, Long folderId,
                                  boolean installed, boolean enabled, String installedVersion) {}

    // ==================== 骨架 ====================

    /**
     * 在项目「插件开发/&lt;id&gt;/」下生成 Web 插件骨架（manifest.json + web/index.html +
     * web/awd-plugin-sdk.js），返回插件源码文件夹的数据库 ID。
     */
    public synchronized Long scaffold(Long projectId, Long userId, String id, String displayName) {
        if (projectId == null || userId == null) {
            throw new IllegalArgumentException(LangText.of("项目与用户 ID 不能为空", "Project and user ID are required"));
        }
        requireValidId(id);
        String name = (displayName == null || displayName.isBlank()) ? id : displayName.trim();

        ProjectFile devRoot = findDevRoot(projectId);
        if (devRoot == null) {
            devRoot = projectFileService.createFolder(projectId, null, DEV_ROOT_FOLDER, userId);
        }
        if (projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, devRoot.getId(), id).isPresent()) {
            throw new IllegalArgumentException(LangText.of("插件项目已存在: ", "Plugin project already exists: ") + id);
        }

        ProjectFile folder = projectFileService.createFolder(projectId, devRoot.getId(), id, userId);
        ProjectFile web = projectFileService.createFolder(projectId, folder.getId(), "web", userId);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", id);
        manifest.put("name", name);
        manifest.put("version", "0.1.0");
        manifest.put("description", "");
        manifest.put("author", "");
        manifest.put("permissions", List.of("file_read"));
        manifest.put("frontendEntry", "web/index.html");
        manifest.put("tools", List.of());
        manifest.put("backendJars", List.of());
        writeProjectFile(projectId, folder.getId(), "manifest.json", "json", userId,
                JSONUtil.toJsonPrettyStr(manifest).getBytes(StandardCharsets.UTF_8));

        String indexHtml = readTemplate("plugin-dev/template-index.html").replace("__PLUGIN_NAME__", name);
        writeProjectFile(projectId, web.getId(), "index.html", "html", userId,
                indexHtml.getBytes(StandardCharsets.UTF_8));
        writeProjectFile(projectId, web.getId(), "awd-plugin-sdk.js", "js", userId,
                readTemplate("plugin-dev/awd-plugin-sdk.js").getBytes(StandardCharsets.UTF_8));

        log.info("Plugin dev scaffold created: project={} id={} folderId={}", projectId, id, folder.getId());
        return folder.getId();
    }

    // ==================== 列表 ====================

    /** 列出项目「插件开发」目录下的插件源码项目及各自的本机安装状态。 */
    public List<DevPluginStatus> status(Long projectId) {
        List<DevPluginStatus> result = new ArrayList<>();
        ProjectFile devRoot = findDevRoot(projectId);
        if (devRoot == null) {
            return result;
        }
        Map<String, PluginService.PluginMetadata> installedMeta = new HashMap<>();
        for (PluginService.PluginMetadata meta : pluginService.getPlugins()) {
            installedMeta.put(meta.getId(), meta);
        }
        for (ProjectFile child : projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, devRoot.getId())) {
            if (!Boolean.TRUE.equals(child.getIsFolder()) || Boolean.TRUE.equals(child.getIsDeleted())) {
                continue;
            }
            String id = child.getName();
            String name = id;
            String version = null;
            JSONObject manifest = readManifestQuietly(projectId, child.getId());
            if (manifest != null) {
                name = manifest.getStr("name", id);
                version = manifest.getStr("version", null);
            }
            boolean installed = new File(new File(new File(pluginsDir), id), DEV_MARKER).isFile();
            PluginService.PluginMetadata meta = installedMeta.get(id);
            boolean enabled = installed && meta != null && pluginService.isEnabled(id);
            result.add(new DevPluginStatus(id, name, version, child.getId(),
                    installed, enabled, meta == null ? null : meta.getVersion()));
        }
        return result;
    }

    // ==================== 安装 ====================

    /**
     * 校验并把插件源码文件夹装进本机 plugins/ 目录（热重扫 + 启用），返回插件 id。
     * 校验失败抛 {@link IllegalArgumentException}，message 是逐条错误明细
     * （给面板展示，也给 AI 工具返回让模型自行修复后重装）。
     */
    public synchronized String install(Long projectId, Long folderId) {
        if (projectId == null || folderId == null) {
            throw new IllegalArgumentException(LangText.of("项目与文件夹 ID 不能为空", "Project and folder ID are required"));
        }
        Optional<ProjectFile> opt = projectFileRepository.findById(folderId);
        if (opt.isEmpty() || Boolean.TRUE.equals(opt.get().getIsDeleted())) {
            throw new IllegalArgumentException(LangText.of("文件夹不存在: ", "Folder not found: ") + folderId);
        }
        ProjectFile folder = opt.get();
        if (!projectId.equals(folder.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件夹不属于该项目", "Folder does not belong to this project"));
        }
        if (!Boolean.TRUE.equals(folder.getIsFolder())) {
            throw new IllegalArgumentException(LangText.of("ID 指向的不是文件夹: ", "Not a folder: ") + folderId);
        }

        String id = folder.getName();
        List<String> errors = new ArrayList<>();
        if (!PLUGIN_ID.matcher(id).matches()) {
            errors.add(LangText.of(
                    "文件夹名不是合法插件 id（小写字母/数字/连字符，2-50 位）: " + id + "。请重命名文件夹。",
                    "Folder name is not a valid plugin id (lowercase letters/digits/hyphens, 2-50 chars): " + id));
        }

        // 收集子树（相对路径 -> 文件记录）。文件名经 ProjectFileService.validateNodeName 入库，
        // 不含路径分隔符，这里再复核一次并拒绝隐藏文件同名于安装标记的情况。
        Map<String, ProjectFile> files = new LinkedHashMap<>();
        collectSubtree(projectId, folder.getId(), "", files, errors, 0);
        if (files.size() > MAX_FILES) {
            errors.add(LangText.of("文件数超限（最多 " + MAX_FILES + " 个）", "Too many files (max " + MAX_FILES + ")"));
        }

        ProjectFile manifestFile = files.get("manifest.json");
        JSONObject manifest = null;
        if (manifestFile == null) {
            errors.add(LangText.of("缺少 manifest.json（必须在插件文件夹根部）", "manifest.json is missing at the plugin folder root"));
        } else {
            try {
                manifest = JSONUtil.parseObj(new String(readBytes(manifestFile), StandardCharsets.UTF_8));
            } catch (Exception e) {
                errors.add(LangText.of("manifest.json 不是合法 JSON: ", "manifest.json is not valid JSON: ") + e.getMessage());
            }
        }
        if (manifest != null) {
            validateManifest(manifest, id, files.keySet(), errors);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }

        File pluginsRoot = new File(pluginsDir);
        File target = new File(pluginsRoot, id);
        if (target.isDirectory() && !new File(target, DEV_MARKER).isFile()) {
            throw new IllegalArgumentException(LangText.of(
                    "本机已装有同名的广场插件（" + id + "），开发安装不覆盖它。请换一个插件 id。",
                    "A marketplace plugin with the same id (" + id + ") is installed; dev install will not overwrite it."));
        }

        // 先落临时目录再原子替换：拷贝中途失败不留半套目录（形制同 PluginMarketService）
        File tmp = new File(pluginsRoot, ".dev-tmp-" + id + "-" + System.nanoTime());
        try {
            long total = 0;
            for (Map.Entry<String, ProjectFile> e : files.entrySet()) {
                byte[] bytes = readBytes(e.getValue());
                total += bytes.length;
                if (bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException(LangText.of(
                            "文件过大（单文件 5MB / 总量 20MB 上限）: " + e.getKey(),
                            "File too large (5MB per file / 20MB total): " + e.getKey()));
                }
                File out = new File(tmp, e.getKey());
                FileUtil.mkdir(out.getParentFile());
                Files.write(out.toPath(), bytes);
            }
            JSONObject marker = new JSONObject();
            marker.set("projectId", projectId);
            marker.set("folderId", folderId);
            marker.set("installedAt", LocalDateTime.now().toString());
            Files.write(new File(tmp, DEV_MARKER).toPath(), marker.toString().getBytes(StandardCharsets.UTF_8));

            if (target.exists()) {
                FileUtil.del(target);
            }
            FileUtil.move(tmp, target, false);
        } catch (IllegalArgumentException e) {
            FileUtil.del(tmp);
            throw e;
        } catch (Exception e) {
            FileUtil.del(tmp);
            throw new IllegalStateException(LangText.of("安装失败: ", "Install failed: ") + e.getMessage(), e);
        }

        pluginService.rescan();
        try {
            pluginService.setEnabled(id, true);
        } catch (Exception e) {
            // 被平台封禁的 id 无法启用；装是装上了，如实上抛比静默装聋好
            throw new IllegalStateException(LangText.of("插件已拷贝但启用失败: ", "Plugin copied but enabling failed: ") + e.getMessage(), e);
        }
        log.info("Dev plugin installed: id={} project={} folderId={} files={}", id, projectId, folderId, files.size());
        return id;
    }

    // ==================== 卸载 ====================

    /** 卸载开发安装的插件（只认带 {@code .awd-dev} 标记的目录，不动项目里的源码）。 */
    public synchronized void uninstall(String id) {
        requireValidId(id);
        File pluginsRoot = new File(pluginsDir);
        File dir = new File(pluginsRoot, id);
        try {
            if (!dir.getCanonicalPath().startsWith(pluginsRoot.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException(LangText.of("非法插件目录: ", "Invalid plugin directory: ") + id);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(LangText.of("路径检查失败: ", "Path check failed: ") + e.getMessage());
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException(LangText.of("插件未安装: ", "Plugin not installed: ") + id);
        }
        if (!new File(dir, DEV_MARKER).isFile()) {
            throw new IllegalArgumentException(LangText.of(
                    "该插件不是开发安装的（无 .awd-dev 标记），请从插件广场卸载。",
                    "Not a dev-installed plugin (no .awd-dev marker); uninstall it from the marketplace instead."));
        }
        FileUtil.del(dir);
        pluginService.rescan();
        log.info("Dev plugin uninstalled: {}", id);
    }

    // ==================== 内部实现 ====================

    private void validateManifest(JSONObject manifest, String folderName, Set<String> paths, List<String> errors) {
        String id = manifest.getStr("id", "");
        if (!folderName.equals(id)) {
            errors.add(LangText.of(
                    "manifest.id（" + id + "）必须与源码文件夹名（" + folderName + "）一致",
                    "manifest.id (" + id + ") must equal the source folder name (" + folderName + ")"));
        }
        String entry = manifest.getStr("frontendEntry", "");
        if (entry == null || !entry.startsWith("web/")) {
            errors.add(LangText.of(
                    "frontendEntry 必须指向 web/ 目录内的文件（如 web/index.html），当前: " + entry,
                    "frontendEntry must point into web/ (e.g. web/index.html), got: " + entry));
        } else if (!paths.contains(entry)) {
            errors.add(LangText.of("frontendEntry 指向的文件不存在: ", "frontendEntry target file is missing: ") + entry);
        }
        JSONArray permissions = manifest.getJSONArray("permissions");
        if (permissions != null) {
            for (Object p : permissions) {
                if (!ALLOWED_PERMISSIONS.contains(String.valueOf(p))) {
                    errors.add(LangText.of("未知权限: ", "Unknown permission: ") + p
                            + LangText.of("（可用: file_read / file_write / network / editor / ai）",
                                          " (allowed: file_read / file_write / network / editor / ai)"));
                }
            }
        }
        // minHostVersion（规范 v2.7 P0）：格式非法报错；宿主可比且不达标也报错（dev 态宿主 "dev" 跳过）
        String minHost = manifest.getStr("minHostVersion", null);
        if (minHost != null && !minHost.isBlank()) {
            if (!com.checkba.util.Semver.isSemver(minHost)) {
                errors.add(LangText.of(
                        "minHostVersion 不是合法的语义化版本号: " + minHost,
                        "minHostVersion is not a valid semver: " + minHost));
            } else if (com.checkba.util.Semver.isSemver(appVersion)
                    && com.checkba.util.Semver.compare(appVersion, minHost) < 0) {
                errors.add(LangText.of(
                        "插件要求宿主版本 ≥ " + minHost + "，当前宿主是 " + appVersion + "，请先升级客户端",
                        "Plugin requires host >= " + minHost + " but current host is " + appVersion));
            }
        }
        // 开发安装只收纯 Web 插件：以下字段任一非空即拒装（安全模型见类注释）
        for (String forbidden : List.of("backendJars", "tools", "skills", "packs")) {
            JSONArray arr = manifest.getJSONArray(forbidden);
            if (arr != null && !arr.isEmpty()) {
                errors.add(LangText.of(
                        "开发安装只支持纯 Web 插件，manifest." + forbidden + " 必须为空（JAR/工具/skill/资源包请走插件广场的审核签名流程）",
                        "Dev install only accepts pure web plugins; manifest." + forbidden + " must be empty"));
            }
        }
        // 证据来源不走 dev 免签直装（规范 v2.8 P3 红线）：SPI 要 JAR、MCP 是数据源接入，
        // 都属于要人工审核的档位
        JSONObject contributes = manifest.getJSONObject("contributes");
        if (contributes != null) {
            JSONArray evidenceSources = contributes.getJSONArray("evidenceSources");
            if (evidenceSources != null && !evidenceSources.isEmpty()) {
                errors.add(LangText.of(
                        "开发安装不支持 contributes.evidenceSources（证据数据源需经插件广场审核签名）",
                        "Dev install does not accept contributes.evidenceSources; publish via the marketplace review flow"));
            }
        }
    }

    /** DFS 收集子树文件（相对路径用 '/'），路径段复核 + 深度熔断。 */
    private void collectSubtree(Long projectId, Long parentId, String prefix,
                                Map<String, ProjectFile> out, List<String> errors, int depth) {
        if (depth > 10) {
            errors.add(LangText.of("目录层级过深（>10）", "Folder nesting too deep (>10)"));
            return;
        }
        for (ProjectFile child : projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, parentId)) {
            if (Boolean.TRUE.equals(child.getIsDeleted())) {
                continue;
            }
            String name = child.getName() == null ? "" : child.getName();
            if (name.isBlank() || name.contains("/") || name.contains("\\") || ".".equals(name) || "..".equals(name)) {
                errors.add(LangText.of("非法文件名: ", "Invalid file name: ") + name);
                continue;
            }
            if (DEV_MARKER.equals(name)) {
                errors.add(LangText.of("文件名与安装标记冲突: ", "File name conflicts with the install marker: ") + name);
                continue;
            }
            String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            if (Boolean.TRUE.equals(child.getIsFolder())) {
                collectSubtree(projectId, child.getId(), rel, out, errors, depth + 1);
            } else {
                out.put(rel, child);
            }
        }
    }

    private byte[] readBytes(ProjectFile pf) {
        String key = pf.getFilePath();
        if (key == null || key.isBlank()) {
            key = pf.getWpsFileId();
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(LangText.of("文件没有存储路径: ", "File has no storage path: ") + pf.getName());
        }
        try (InputStream is = storageServiceFactory.getStorageService().load(key).getInputStream()) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException(LangText.of("读取文件失败: ", "Failed to read file: ") + pf.getName() + " - " + e.getMessage());
        }
    }

    private JSONObject readManifestQuietly(Long projectId, Long folderId) {
        try {
            Optional<ProjectFile> mf = projectFileRepository
                    .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, folderId, "manifest.json");
            if (mf.isEmpty() || Boolean.TRUE.equals(mf.get().getIsFolder())) {
                return null;
            }
            return JSONUtil.parseObj(new String(readBytes(mf.get()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private ProjectFile findDevRoot(Long projectId) {
        return projectFileRepository
                .findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, null, DEV_ROOT_FOLDER)
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()))
                .orElse(null);
    }

    private void writeProjectFile(Long projectId, Long parentId, String name, String fileType, Long userId, byte[] bytes) {
        ProjectFile pf = projectFileService.createFile(projectId, parentId, name, fileType,
                (long) bytes.length, null, null, userId);
        try {
            storageServiceFactory.getStorageService().save(pf.getFilePath(), new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("写入文件失败: ", "Failed to write file: ") + name + " - " + e.getMessage(), e);
        }
    }

    private String readTemplate(String classpath) {
        try (InputStream is = new ClassPathResource(classpath).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Missing classpath resource: " + classpath, e);
        }
    }

    private static void requireValidId(String id) {
        if (id == null || !PLUGIN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(LangText.of(
                    "插件 id 不合法（小写字母/数字/连字符，2-50 位）: " + id,
                    "Invalid plugin id (lowercase letters/digits/hyphens, 2-50 chars): " + id));
        }
    }
}
