package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to manage backend plugins.
 * Scans 'plugins/' directory for JAR files and registers AI tools.
 *
 * Manifest 规范 v1 见 docs/PLUGIN_SPEC.md；示例插件见 examples/hello-plugin/。
 * 启停状态持久化在 system_setting 表（key = {@link #DISABLED_KEY}，值为被禁用插件 id 的 JSON 数组，
 * 默认全部启用）。{@link #isEnabled(String)} 供将来 ToolRegistry 过滤禁用插件的工具
 * （接入点见 docs/AI_ARCHITECTURE.md 的 Phase 3 TODO，本轮不改 ToolRegistry）。
 */
@Service
@Slf4j
public class PluginService {

    /** system_setting 中存放被禁用插件 id 列表（JSON 数组）的 key */
    public static final String DISABLED_KEY = "ai.plugins.disabled";

    /** manifest 规范 v1 已定义的权限值，未知值仅告警不拒绝（向前兼容） */
    private static final Set<String> KNOWN_PERMISSIONS =
            Set.of("file_read", "file_write", "network", "editor");

    @Getter
    private final List<PluginMetadata> plugins = new ArrayList<>();

    @Getter
    private final Map<String, Object> pluginTools = new HashMap<>(); // toolName -> toolObject

    @Getter
    private final List<ToolSpecification> toolSpecifications = new ArrayList<>();

    /** toolName -> pluginId，供将来 ToolRegistry 按插件启停过滤工具 */
    private final Map<String, String> toolToPluginId = new HashMap<>();

    /** 被禁用插件 id 集合（内存缓存，与 system_setting 同步） */
    private final Set<String> disabledPluginIds = ConcurrentHashMap.newKeySet();

    private final SystemSettingService systemSettingService;

    private final String pluginsDir;

    @org.springframework.beans.factory.annotation.Autowired
    public PluginService(SystemSettingService systemSettingService,
                         @Value("${ai.plugins.dir:plugins}") String pluginsDir) {
        this.systemSettingService = systemSettingService;
        this.pluginsDir = pluginsDir;
    }

    /** 兼容构造器：仅供既有单测直接 new 使用（无持久化，启停状态只存内存） */
    public PluginService() {
        this(null, "plugins");
    }

    @lombok.Data
    public static class PluginMetadata {
        private String id;
        private String name;
        private String version;
        private String description;
        private String icon;
        private String author;
        private String homepage;
        private String frontendEntry;
        private List<String> backendJars;
        /** 声明需要的能力：file_read / file_write / network / editor */
        private List<String> permissions;
        /** 插件提供的工具清单（名称 + 中文描述） */
        private List<PluginToolInfo> tools;
    }

    @lombok.Data
    public static class PluginToolInfo {
        private String name;
        private String description;
    }

    @PostConstruct
    public void init() {
        loadDisabledState();
        loadPlugins();
    }

    /**
     * 重新扫描插件目录：清空已加载的元数据与工具后全量重载。
     * 注意：已由旧 ClassLoader 加载的类不会被卸载（MVP 可接受），重扫主要用于发现新装插件。
     */
    public synchronized void rescan() {
        plugins.clear();
        pluginTools.clear();
        toolSpecifications.clear();
        toolToPluginId.clear();
        loadDisabledState();
        loadPlugins();
        log.info("Plugin rescan done: {} plugins, {} tools", plugins.size(), pluginTools.size());
    }

    /**
     * 插件是否启用（默认启用，禁用名单持久化在 system_setting）。
     * 供 PluginController 与将来的 ToolRegistry 查询。
     */
    public boolean isEnabled(String pluginId) {
        return !disabledPluginIds.contains(pluginId);
    }

    /**
     * 启用 / 禁用插件并持久化。
     * @throws IllegalArgumentException pluginId 不在已加载插件列表中
     */
    public synchronized void setEnabled(String pluginId, boolean enabled) {
        boolean known = plugins.stream().anyMatch(p -> Objects.equals(p.getId(), pluginId));
        if (!known) {
            throw new IllegalArgumentException("Unknown plugin: " + pluginId);
        }
        if (enabled) {
            disabledPluginIds.remove(pluginId);
        } else {
            disabledPluginIds.add(pluginId);
        }
        if (systemSettingService != null) {
            systemSettingService.set(DISABLED_KEY,
                    cn.hutool.json.JSONUtil.toJsonStr(new TreeSet<>(disabledPluginIds)));
        }
        log.info("Plugin {} {}", pluginId, enabled ? "enabled" : "disabled");
    }

    /** 工具所属的插件 id（内置工具不在此映射中，返回 null） */
    public String getPluginIdForTool(String toolName) {
        return toolToPluginId.get(toolName);
    }

    private void loadDisabledState() {
        disabledPluginIds.clear();
        if (systemSettingService == null) {
            return;
        }
        try {
            String json = systemSettingService.get(DISABLED_KEY, "[]");
            List<String> ids = cn.hutool.json.JSONUtil.toList(json, String.class);
            if (ids != null) {
                disabledPluginIds.addAll(ids);
            }
        } catch (Exception e) {
            log.error("Failed to load plugin disabled state, default to all enabled", e);
        }
    }

    public void loadPlugins() {
        File dir = new File(pluginsDir);
        if (!dir.exists()) {
            dir.mkdirs();
            return;
        }

        File[] pluginDirs = dir.listFiles(File::isDirectory);
        if (pluginDirs == null || pluginDirs.length == 0) {
            log.info("No plugin directories found in {}", pluginsDir);
            return;
        }

        for (File pluginDir : pluginDirs) {
            try {
                File manifestFile = new File(pluginDir, "manifest.json");
                if (!manifestFile.exists()) continue;

                log.info("Loading plugin metadata from: {}", pluginDir.getName());
                String json = cn.hutool.core.io.FileUtil.readUtf8String(manifestFile);
                PluginMetadata meta = parseManifest(json);
                if (meta == null) {
                    log.error("Invalid manifest (missing id), skip plugin dir: {}", pluginDir.getName());
                    continue;
                }
                boolean duplicated = plugins.stream().anyMatch(p -> Objects.equals(p.getId(), meta.getId()));
                if (duplicated) {
                    log.warn("Duplicate plugin id '{}', skip plugin dir: {}", meta.getId(), pluginDir.getName());
                    continue;
                }
                plugins.add(meta);

                // Load associated JARs if any
                if (meta.getBackendJars() != null) {
                    for (String jarName : meta.getBackendJars()) {
                        File jarFile = new File(pluginDir, jarName);
                        if (jarFile.exists()) {
                            loadJar(jarFile, meta.getId());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to load plugin: " + pluginDir.getName(), e);
            }
        }
    }

    /**
     * 解析 manifest.json（规范 v1，见 docs/PLUGIN_SPEC.md）。
     * @return 解析后的元数据；id 缺失时返回 null（插件必须有稳定 id 才能启停）
     */
    PluginMetadata parseManifest(String json) {
        PluginMetadata meta = cn.hutool.json.JSONUtil.toBean(json, PluginMetadata.class);
        if (meta.getId() == null || meta.getId().isBlank()) {
            return null;
        }
        if (meta.getPermissions() != null) {
            for (String p : meta.getPermissions()) {
                if (!KNOWN_PERMISSIONS.contains(p)) {
                    log.warn("Plugin {} declares unknown permission '{}' (spec v1 knows: {})",
                            meta.getId(), p, KNOWN_PERMISSIONS);
                }
            }
        }
        return meta;
    }

    private void loadJar(File jar, String pluginId) throws IOException, ClassNotFoundException {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, this.getClass().getClassLoader());

        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
            Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".class")) {
                    String className = name.replace("/", ".").substring(0, name.length() - 6);
                    try {
                        Class<?> cls = loader.loadClass(className);
                        // Check if class has methods with @Tool annotation
                        boolean hasTools = Arrays.stream(cls.getDeclaredMethods())
                                .anyMatch(m -> m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class));

                        if (hasTools) {
                            log.info("Found tool class in plugin: {}", className);
                            // Instantiate and register
                            Object instance = cls.getDeclaredConstructor().newInstance();
                            registerToolObject(instance, pluginId);
                        }
                    } catch (Throwable e) {
                        // Skip classes that can't be loaded (e.g. missing dependencies)
                        log.debug("Skipping class {}: {}", className, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error scanning JAR {}: {}", jar.getName(), e.getMessage());
        }
    }

    /**
     * Registers a tool object (e.g., from a JAR or hardcoded).
     */
    public void registerToolObject(Object toolObject) {
        registerToolObject(toolObject, null);
    }

    private void registerToolObject(Object toolObject, String pluginId) {
        try {
            List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(toolObject);
            for (ToolSpecification spec : specs) {
                log.info("Registered dynamic tool: {}", spec.name());
                pluginTools.put(spec.name(), toolObject);
                toolSpecifications.add(spec);
                if (pluginId != null) {
                    toolToPluginId.put(spec.name(), pluginId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to register tool object: " + toolObject.getClass().getName(), e);
        }
    }
}
