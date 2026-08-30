package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.SystemSettingService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件声明式贡献点的宿主落点（规范 v2.9 P4）：文书模板 / 样式画像 / 设置。
 *
 * <p>三条通用规则：只对**已启用**插件生效；声明文件读取按 canonical path 校验必须落在
 * 插件目录内（parseManifest 的相对路径守卫是第一道，这里是第二道）；展示文案经
 * {@link PluginService#localize} 解析 l10n 的 %key% 引用。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PluginContributionService {

    /** 全局默认画像的选择位：值形如 {@code <pluginId>:<profileId>}，空 = 未选 */
    public static final String STYLE_PROFILE_SELECTED_KEY = "ai.styleProfile.selected";

    /** 模板文件上限：docx 模板正常几十 KB 到几 MB，20MB 与插件包受理线一致 */
    static final long MAX_TEMPLATE_BYTES = 20L * 1024 * 1024;

    private final PluginService pluginService;
    private final SystemSettingService systemSettingService;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;

    // ==================== 文书模板 ====================

    public record ContributedTemplate(String pluginId, String id, String name, String genre,
                                      String description, String language, String fileExt) {
    }

    /** 全部已启用插件贡献的模板（按当前应用语言过滤 language 声明、解析 l10n） */
    public List<ContributedTemplate> listTemplates() {
        String lang = com.checkba.service.LangText.isEnglish() ? "en-US" : "zh-CN";
        List<ContributedTemplate> out = new ArrayList<>();
        for (PluginService.PluginMetadata meta : pluginService.getPlugins()) {
            if (!pluginService.isEnabled(meta.getId()) || meta.getContributes() == null
                    || meta.getContributes().getTemplates() == null) {
                continue;
            }
            for (PluginService.TemplateDecl t : meta.getContributes().getTemplates()) {
                if (t.getLanguage() != null && !t.getLanguage().isBlank() && !lang.equals(t.getLanguage())) {
                    continue;
                }
                out.add(new ContributedTemplate(meta.getId(), t.getId(),
                        pluginService.localize(meta.getId(), t.getName()),
                        t.getGenre(),
                        pluginService.localize(meta.getId(), t.getDescription()),
                        t.getLanguage(), extOf(t.getFile())));
            }
        }
        return out;
    }

    /**
     * 从贡献模板创建项目文件（RENAME 冲突策略，同名自动加 (n)）。
     * 调用方负责鉴权（登录 + 项目写权限），这里只做插件/模板/路径三重校验。
     */
    public ProjectFile createFromTemplate(Long projectId, Long userId, String pluginId,
                                          String templateId, Long parentId, String nameOverride) {
        PluginService.TemplateDecl decl = findTemplate(pluginId, templateId);
        byte[] bytes = readContributedFile(pluginId, decl.getFile());
        String ext = extOf(decl.getFile());
        String base = nameOverride != null && !nameOverride.isBlank()
                ? nameOverride.trim()
                : String.valueOf(pluginService.localize(pluginId, decl.getName()));
        String name = base.toLowerCase().endsWith("." + ext) ? base : base + "." + ext;
        ProjectFile file = projectFileService.createFile(projectId, parentId, name, ext,
                (long) bytes.length, null, null, userId, ProjectFileService.ConflictPolicy.RENAME);
        try {
            storageServiceFactory.getStorageService().save(file.getFilePath(), new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("模板内容写入失败: " + e.getMessage(), e);
        }
        log.info("plugin {} template {} -> project {} file {}", pluginId, templateId, projectId, file.getId());
        return file;
    }

    private PluginService.TemplateDecl findTemplate(String pluginId, String templateId) {
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        if (meta == null || !pluginService.isEnabled(pluginId) || meta.getContributes() == null
                || meta.getContributes().getTemplates() == null) {
            throw new IllegalArgumentException("插件不存在或未启用: " + pluginId);
        }
        return meta.getContributes().getTemplates().stream()
                .filter(t -> t.getId().equals(templateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + templateId));
    }

    // ==================== 样式画像 ====================

    public record ContributedStyleProfile(String pluginId, String id, String name, boolean selected) {
    }

    public List<ContributedStyleProfile> listStyleProfiles() {
        String selected = systemSettingService.get(STYLE_PROFILE_SELECTED_KEY, "");
        List<ContributedStyleProfile> out = new ArrayList<>();
        for (PluginService.PluginMetadata meta : pluginService.getPlugins()) {
            if (!pluginService.isEnabled(meta.getId()) || meta.getContributes() == null
                    || meta.getContributes().getStyleProfiles() == null) {
                continue;
            }
            for (PluginService.StyleProfileDecl sp : meta.getContributes().getStyleProfiles()) {
                String ref = meta.getId() + ":" + sp.getId();
                out.add(new ContributedStyleProfile(meta.getId(), sp.getId(),
                        pluginService.localize(meta.getId(), sp.getName()), ref.equals(selected)));
            }
        }
        return out;
    }

    /** 选定/清除全局默认画像；ref 形如 {@code <pluginId>:<profileId>}，null/空 = 清除 */
    public void selectStyleProfile(String ref) {
        if (ref == null || ref.isBlank()) {
            systemSettingService.set(STYLE_PROFILE_SELECTED_KEY, "");
            return;
        }
        String[] parts = ref.split(":", 2);
        if (parts.length != 2 || findStyleProfileDecl(parts[0], parts[1]) == null) {
            throw new IllegalArgumentException("画像不存在或所属插件未启用: " + ref);
        }
        // 选定前解析一遍：坏 JSON 的画像不允许被选中（否则每次导出都 WARN 一遍）
        StyleProfiles.parse(new String(readContributedFile(parts[0], findStyleProfileDecl(parts[0], parts[1]).getFile()),
                java.nio.charset.StandardCharsets.UTF_8));
        systemSettingService.set(STYLE_PROFILE_SELECTED_KEY, ref);
    }

    /**
     * 当前选中的插件画像 JSON；未选/插件禁用/文件损坏一律 null + WARN（StyleProfileResolver
     * 据此退到下一级，不炸文档导出）。
     */
    public String selectedStyleProfileJson() {
        String ref = systemSettingService.get(STYLE_PROFILE_SELECTED_KEY, "");
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            String[] parts = ref.split(":", 2);
            if (parts.length != 2 || !pluginService.isEnabled(parts[0])) {
                log.warn("选中的插件画像 {} 不可用（插件禁用/引用非法），退默认链", ref);
                return null;
            }
            PluginService.StyleProfileDecl decl = findStyleProfileDecl(parts[0], parts[1]);
            if (decl == null) {
                log.warn("选中的插件画像 {} 已不存在，退默认链", ref);
                return null;
            }
            String json = new String(readContributedFile(parts[0], decl.getFile()),
                    java.nio.charset.StandardCharsets.UTF_8);
            StyleProfiles.parse(json);
            return json;
        } catch (Exception e) {
            log.warn("选中的插件画像 {} 读取/解析失败，退默认链: {}", ref, e.getMessage());
            return null;
        }
    }

    private PluginService.StyleProfileDecl findStyleProfileDecl(String pluginId, String profileId) {
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        if (meta == null || !pluginService.isEnabled(pluginId) || meta.getContributes() == null
                || meta.getContributes().getStyleProfiles() == null) {
            return null;
        }
        return meta.getContributes().getStyleProfiles().stream()
                .filter(sp -> sp.getId().equals(profileId)).findFirst().orElse(null);
    }

    // ==================== 设置 ====================

    /** 设置声明 + 当前值（secret 只回显尾 4 位）；给广场详情页渲染表单 */
    public List<Map<String, Object>> settingsView(String pluginId) {
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (meta == null || meta.getSettings() == null) {
            return out;
        }
        for (PluginService.PluginSettingDecl d : meta.getSettings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", d.getKey());
            row.put("type", d.getType());
            row.put("label", pluginService.localize(pluginId, d.getLabel()));
            row.put("description", pluginService.localize(pluginId, d.getDescription()));
            row.put("options", d.getOptions());
            boolean secret = Boolean.TRUE.equals(d.getSecret());
            row.put("secret", secret);
            String value = currentValue(pluginId, d);
            row.put("value", secret ? mask(value) : value);
            out.add(row);
        }
        return out;
    }

    /** 保存设置（按声明校验类型；未声明的键拒绝）。写入只走这条——配置权在用户手里 */
    public void saveSettings(String pluginId, Map<String, Object> values) {
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        if (meta == null || meta.getSettings() == null) {
            throw new IllegalArgumentException("插件没有可配置项: " + pluginId);
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            PluginService.PluginSettingDecl d = meta.getSettings().stream()
                    .filter(x -> x.getKey().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未声明的设置项: " + e.getKey()));
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            validateValue(d, v);
            systemSettingService.set("plugin." + pluginId + "." + d.getKey(), v);
        }
    }

    /**
     * 桥 {@code settings.get} 的读出口：只认声明过的键；secret 的键不进插件桥
     * （返回 null，桥端回 permission_denied——密钥给宿主侧服务用，不给 iframe）。
     */
    public String settingValueForBridge(String pluginId, String key) {
        PluginService.PluginMetadata meta = pluginService.getPlugin(pluginId);
        if (meta == null || meta.getSettings() == null) {
            return null;
        }
        PluginService.PluginSettingDecl d = meta.getSettings().stream()
                .filter(x -> x.getKey().equals(key)).findFirst().orElse(null);
        if (d == null || Boolean.TRUE.equals(d.getSecret())) {
            return null;
        }
        String v = currentValue(pluginId, d);
        return v == null ? "" : v;
    }

    private String currentValue(String pluginId, PluginService.PluginSettingDecl d) {
        String def = d.getDefaultValue() == null ? null : String.valueOf(d.getDefaultValue());
        return systemSettingService.get("plugin." + pluginId + "." + d.getKey(), def);
    }

    private static void validateValue(PluginService.PluginSettingDecl d, String v) {
        switch (d.getType()) {
            case "boolean" -> {
                if (!"true".equals(v) && !"false".equals(v) && !v.isEmpty()) {
                    throw new IllegalArgumentException(d.getKey() + " 需要 true/false");
                }
            }
            case "number" -> {
                if (!v.isEmpty()) {
                    try {
                        Double.parseDouble(v);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(d.getKey() + " 需要数字");
                    }
                }
            }
            case "select" -> {
                if (!v.isEmpty() && (d.getOptions() == null || !d.getOptions().contains(v))) {
                    throw new IllegalArgumentException(d.getKey() + " 必须是候选值之一");
                }
            }
            default -> {
                if (v.length() > 4000) {
                    throw new IllegalArgumentException(d.getKey() + " 超过 4000 字符上限");
                }
            }
        }
    }

    private static String mask(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        return v.length() <= 4 ? "****" : "****" + v.substring(v.length() - 4);
    }

    // ==================== 共用 ====================

    /** 读插件目录内的声明文件；canonical path 必须落在插件目录之下（第二道逃逸闸） */
    byte[] readContributedFile(String pluginId, String relFile) {
        File pluginDir = pluginService.getPluginDir(pluginId);
        if (pluginDir == null) {
            throw new IllegalArgumentException("插件目录不存在: " + pluginId);
        }
        try {
            File target = new File(pluginDir, relFile);
            String canonical = target.getCanonicalPath();
            if (!canonical.startsWith(pluginDir.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("声明文件路径逃逸插件目录: " + relFile);
            }
            if (!target.isFile() || target.length() > MAX_TEMPLATE_BYTES) {
                throw new IllegalArgumentException("声明文件不存在或超限: " + relFile);
            }
            return Files.readAllBytes(target.toPath());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("声明文件读取失败: " + e.getMessage(), e);
        }
    }

    private static String extOf(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "" : file.substring(dot + 1).toLowerCase();
    }
}
