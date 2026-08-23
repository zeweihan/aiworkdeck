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
 * Manifest 规范 v2 见 docs/PLUGIN_SPEC.md；示例插件见 examples/hello-plugin/。
 * 启停状态持久化在 system_setting 表（key = {@link #DISABLED_KEY}，值为被禁用插件 id 的 JSON 数组，
 * 默认全部启用）。{@link #isEnabled(String)} 由 ToolRegistry 在三处消费点过滤禁用插件的工具；
 * {@link #missingPermissionsForTool(String)} 由 ToolRegistry 在分发插件工具前做权限校验（规范 v2）。
 */
@Service
@Slf4j
public class PluginService {

    /** system_setting 中存放被禁用插件 id 列表（JSON 数组）的 key */
    public static final String DISABLED_KEY = "ai.plugins.disabled";

    /** manifest 规范 v1 已定义的权限值，未知值仅告警不拒绝（向前兼容） */
    private static final Set<String> KNOWN_PERMISSIONS =
            Set.of("file_read", "file_write", "network", "editor");

    /** manifest.packs 里的 pack id 规则，与 NativePackService / PluginMarketService 同一套 */
    private static final java.util.regex.Pattern PACK_ID =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    // 并发安全：rescan() 会 clear()+重填这些集合，而 ToolRegistry 在高频请求线程上无同步地遍历读取，
    // 普通 ArrayList/HashMap 会抛 ConcurrentModificationException / 读到半空状态。
    @Getter
    private final List<PluginMetadata> plugins = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Getter
    private final Map<String, Object> pluginTools = new ConcurrentHashMap<>(); // toolName -> toolObject

    @Getter
    private final List<ToolSpecification> toolSpecifications = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** toolName -> pluginId，供将来 ToolRegistry 按插件启停过滤工具 */
    private final Map<String, String> toolToPluginId = new ConcurrentHashMap<>();

    /** pluginId -> 插件目录：禁用状态下不加载 JAR，之后启用时据此补加载 */
    private final Map<String, File> pluginDirById = new ConcurrentHashMap<>();

    /**
     * 当前这一代已加载的插件 JAR ClassLoader。loadJar() 每次都 new 一个 URLClassLoader，
     * 从来没有配对的 close()——rescan()/热重载/装卸插件反复调用，长期运行的服务器进程
     * 上会不断攒 fd 与已加载类的元数据。
     *
     * <p>不在 loadJar() 里当场关：那会让刚加载出来的插件类立刻失效（工具对象若懒加载
     * 同一 JAR 里此刻还没碰过的辅助类会失败——URLClassLoader.close() 不影响已加载的类，
     * 但会让该 loader 之后再也读不到 jar 里的新类/资源）。改成在 rescan() 里统一处理：
     * 新一代通过 loadPlugins() 完整重建、注册表已经整体换过、ToolRegistry 缓存也失效之后，
     * 上一代不再可能被任何新请求经 pluginTools 查到，这时才安全关闭。
     *
     * <p>本字段的读写全部发生在 synchronized(this) 的方法里（rescan / setEnabled 都是），
     * 不需要并发安全的集合类型。
     */
    private final List<URLClassLoader> loadedClassLoaders = new ArrayList<>();

    /**
     * 被平台封禁的插件 id -> 原因（由 PluginRevocationService 从注册表同步）。
     * 命中者强制禁用且不允许用户重新启用，见 docs/PLUGIN_DISTRIBUTION.md §8。
     */
    private final Map<String, String> revokedPluginIds = new ConcurrentHashMap<>();

    /** 被禁用插件 id 集合（内存缓存，与 system_setting 同步） */
    private final Set<String> disabledPluginIds = ConcurrentHashMap.newKeySet();

    /**
     * 禁用名单内存缓存的 TTL（毫秒）：同 JVM 内 setEnabled 即时生效；
     * 外部直接改库（或多实例）在 TTL 内收敛。工具调用高频路径不打库。
     */
    @Value("${ai.plugins.disabled-cache-ttl-ms:5000}")
    long disabledCacheTtlMs = 5000;

    private volatile long disabledStateRefreshedAt = 0L;

    private final SystemSettingService systemSettingService;

    private final String pluginsDir;

    /**
     * 反向依赖 ToolRegistry，仅用于 rescan() 后使其插件工具缓存失效（见该字段注入点注释）。
     * 字段注入 + {@code required=false}：ToolRegistry 的构造器已经依赖 PluginService，
     * 若在这里改成构造器注入会形成启动死环（本仓 SubAgentTools 也用同一招 @Lazy 破环）；
     * required=false 使大量 {@code new PluginService(...)} 直接构造的既有测试不受影响
     * ——字段停留 null，rescan() 对 null 判空跳过即可，测试无需关心这层缓存失效。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ToolRegistry toolRegistry;

    /**
     * 反向依赖 SkillRegistry，仅用于 rescan() 后让它把插件携带的 skill 重新拉一遍。
     * 注入方式与上面的 ToolRegistry 同款（@Lazy + required=false）——SkillRegistry
     * 的构造器依赖 PluginService，构造器注入会成环；直接 new PluginService(...) 的
     * 既有测试则停留 null，rescan() 判空跳过。
     *
     * <p>为什么必须在这里连上：插件携带的 skill 目录是 loadPlugins() 扫出来的，
     * SkillRegistry 只在自己 @PostConstruct 与 rescan() 时来拉一次。装完/启用完插件
     * 只重扫了插件侧，skill 侧不动——工具注册上了、skill 却要等下次重启才出现，
     * 用户看到的就是「广场装完、插件开了，但让它干活它不认」。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.checkba.service.ai.skill.SkillRegistry skillRegistry;

    /**
     * 插件宿主 SPI（规范 v2.4 §4/§11）：实例化出的工具类若实现 HostAware，注入按插件 id 绑定的 PluginHost。
     * 用 ObjectProvider 懒取——PluginHostFactory 背后挂着 EditorBridgeService/ProjectFileService 一串，
     * 构造器注入会把本类拖进启动死环；required=false 让直接 new 的测试照旧。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.checkba.service.plugin.PluginHostFactory> pluginHostFactoryProvider;

    /** 供测试直接装配。 */
    void setPluginHostFactory(com.checkba.service.plugin.PluginHostFactory factory) {
        this.pluginHostFactoryProvider = new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public com.checkba.service.plugin.PluginHostFactory getObject(Object... args) { return factory; }
            @Override public com.checkba.service.plugin.PluginHostFactory getIfAvailable() { return factory; }
            @Override public com.checkba.service.plugin.PluginHostFactory getIfUnique() { return factory; }
            @Override public com.checkba.service.plugin.PluginHostFactory getObject() { return factory; }
        };
    }

    /**
     * 实例化后的钩子：实现了 {@link com.checkba.plugin.api.HostAware} 的工具类拿到宿主门面。
     * 宿主工厂不可用（测试直接 new、或启动早期）时只记 WARN——插件照常注册，只是拿不到 host。
     */
    void injectHostIfAware(Object instance, String pluginId) {
        if (!(instance instanceof com.checkba.plugin.api.HostAware aware)) {
            return;
        }
        com.checkba.service.plugin.PluginHostFactory factory =
                pluginHostFactoryProvider != null ? pluginHostFactoryProvider.getIfAvailable() : null;
        if (factory == null) {
            log.warn("Plugin {} tool {} implements HostAware but no PluginHostFactory is available; host not injected",
                    pluginId, instance.getClass().getName());
            return;
        }
        try {
            aware.setHost(factory.forPlugin(pluginId));
            log.info("Injected PluginHost into {} (plugin {})", instance.getClass().getName(), pluginId);
        } catch (Throwable t) {
            // 插件的 setHost 抛了：工具照常注册（没有 host 而已），别让一个插件的构造期错误吞掉整次扫描
            log.warn("Plugin {} tool {} setHost failed, host not injected: {}",
                    pluginId, instance.getClass().getName(), t.toString());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PluginService(SystemSettingService systemSettingService,
                         @Value("${ai.plugins.dir:plugins}") String pluginsDir) {
        this.systemSettingService = systemSettingService;
        this.pluginsDir = pluginsDir;
    }

    /** 供测试直接装配 ToolRegistry（生产环境由 Spring 按上面的 @Autowired 字段注入）。 */
    void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 供测试直接装配 SkillRegistry（生产环境由 Spring 按上面的 @Autowired 字段注入）。 */
    void setSkillRegistry(com.checkba.service.ai.skill.SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 供测试查看当前这一代插件 JAR ClassLoader 的快照。 */
    List<URLClassLoader> loadedClassLoaders() {
        return new ArrayList<>(loadedClassLoaders);
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
        /**
         * 前端入口（规范 v2.3 激活）：
         * <ul>
         *   <li>{@code web/index.html} 这样的相对路径 = Web 插件，由 PluginWebController 静态服务；
         *       扫描时校验必须落在插件目录的 {@code web/} 之下且文件存在，否则置空并记 WARN。</li>
         *   <li>{@code http(s)://} 绝对 URL = 旧形态，原样透传给前端 iframe（不校验、不改行为）。</li>
         * </ul>
         */
        private String frontendEntry;
        private List<String> backendJars;
        /**
         * 依赖的原生资源包 id 列表（规范 v2.3，见 docs/NATIVE_PACK_DISTRIBUTION.md §11.4）。
         * 在线安装该插件成功后逐个异步安装；pack 自己有状态与重试面，装失败不回滚插件。
         */
        private List<String> packs;
        /** 声明需要的能力：file_read / file_write / network / editor */
        private List<String> permissions;
        /** 插件提供的工具清单（名称 + 中文描述） */
        private List<PluginToolInfo> tools;
        /** 插件携带的 skill 子目录名列表（规范 v2.1，见 docs/SKILL_SPEC.md） */
        private List<String> skills;
        /**
         * 上手引导（规范 v2.5，可选）：没有 {@code frontendEntry} 的纯工具/skill 插件装完之后
         * 左栏打开的是宿主渲染的「启动面板」，内容就来自这里。缺省 null，前端按描述与工具清单兜底。
         */
        private PluginGuide guide;
    }

    /** manifest.guide：简介 + 步骤 + 一键动作（动作 = 把 prompt 以 AGENT 模式发进 AI 对话） */
    @lombok.Data
    public static class PluginGuide {
        private String intro;
        private List<String> steps;
        private List<PluginQuickAction> quickActions;
    }

    @lombok.Data
    public static class PluginQuickAction {
        private String label;
        private String prompt;
        /** 按钮下方一句话提示（比如「先在文件树里选中底稿根文件夹」），可空 */
        private String hint;
    }

    /** 插件携带的一个 skill 目录（交给 SkillRegistry 注册，见 docs/SKILL_SPEC.md） */
    public record PluginSkillDir(File dir, String pluginId) {
    }

    /** 扫描时收集到的插件 skill 目录，SkillRegistry 启动/重扫时拉取（同上：rescan 会 clear+重填） */
    @Getter
    private final List<PluginSkillDir> pluginSkillDirs = new java.util.concurrent.CopyOnWriteArrayList<>();

    @lombok.Data
    public static class PluginToolInfo {
        private String name;
        private String description;
        /** 该工具运行所需的能力（规范 v2）：缺省视为不需要任何敏感能力（v1 兼容） */
        private List<String> permissions;
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
        // 上一代 loader 留到新一代完整接管注册表之后才关，见 loadedClassLoaders 字段注释。
        List<URLClassLoader> previousGeneration = new ArrayList<>(loadedClassLoaders);
        loadedClassLoaders.clear();

        plugins.clear();
        pluginTools.clear();
        toolSpecifications.clear();
        toolToPluginId.clear();
        pluginSkillDirs.clear();
        pluginDirById.clear();
        loadDisabledState();
        loadPlugins();
        // 插件更新/卸载后 loadPlugins() 可能已经把同名工具换成了新 bean，
        // 必须让 ToolRegistry 那层懒加载缓存失效，否则旧 bean 会继续被分发执行。
        if (toolRegistry != null) {
            toolRegistry.invalidatePluginToolCache();
        }
        // 插件带来的 skill 也要跟着重来一遍——否则 skill 要等下次重启才出现（见字段注释）。
        // SkillRegistry.rescan() 只读 getPluginSkillDirs()/isEnabled()，两者都不持本对象的锁，
        // 在这里同步调用不会与 PluginService 形成锁序环。
        if (skillRegistry != null) {
            skillRegistry.rescan();
        }
        log.info("Plugin rescan done: {} plugins, {} tools", plugins.size(), pluginTools.size());

        // 新一代已经完整接管，上一代不可能再被任何请求经 pluginTools 查到，此时关闭安全。
        for (URLClassLoader old : previousGeneration) {
            try {
                old.close();
            } catch (IOException e) {
                log.debug("关闭上一代插件 ClassLoader 失败（忽略）: {}", e.getMessage());
            }
        }
    }

    /**
     * 插件是否启用（默认启用，禁用名单持久化在 system_setting）。
     * 供 PluginController 与 ToolRegistry 查询；内存缓存超过 TTL 时从配置表重读。
     */
    public boolean isEnabled(String pluginId) {
        maybeRefreshDisabledState();
        return !disabledPluginIds.contains(pluginId);
    }

    private void maybeRefreshDisabledState() {
        if (systemSettingService == null) {
            return;
        }
        if (System.currentTimeMillis() - disabledStateRefreshedAt < disabledCacheTtlMs) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - disabledStateRefreshedAt < disabledCacheTtlMs) {
                return;
            }
            loadDisabledState();
        }
    }

    /**
     * 插件工具在分发前的权限校验（规范 v2）：工具所需权限（manifest tools[].permissions）
     * 必须全部包含在插件声明的 permissions 中。
     *
     * @return 所需但未在插件 permissions 中声明的权限；空列表 = 校验通过。
     *         内置工具（不在 toolToPluginId 映射中）与未声明 permissions 的工具（v1 兼容）恒为通过。
     */
    public List<String> missingPermissionsForTool(String toolName) {
        String pluginId = toolToPluginId.get(toolName);
        if (pluginId == null) {
            return List.of();
        }
        PluginMetadata meta = plugins.stream()
                .filter(p -> Objects.equals(p.getId(), pluginId))
                .findFirst().orElse(null);
        if (meta == null || meta.getTools() == null) {
            return List.of();
        }
        List<String> required = meta.getTools().stream()
                .filter(t -> Objects.equals(t.getName(), toolName))
                .findFirst()
                .map(PluginToolInfo::getPermissions)
                .orElse(null);
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        Set<String> declared = meta.getPermissions() != null
                ? new HashSet<>(meta.getPermissions()) : Set.of();
        return required.stream().filter(p -> !declared.contains(p)).toList();
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
        // 平台封禁是强制的：用户可以卸载，但不能把被封禁的插件重新启用
        if (enabled && revokedPluginIds.containsKey(pluginId)) {
            throw new IllegalStateException(
                    "该插件已被平台下架：" + revokedPluginIds.get(pluginId) + "，无法启用，建议卸载");
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
        // 启用时补加载 JAR：启动阶段被禁用的插件跳过了加载，否则重新启用后工具永远不出现。
        // 反向不成立——JVM 无法卸载已加载的类，禁用只能让工具不可见，要彻底停掉需重启。
        if (enabled) {
            loadJarsIfAbsent(pluginId);
            // 启用插件 = 启用它携带的 skill（见 SkillRegistry.enableSkillsFromPlugin 的注释）。
            // 先 rescan 让刚装的插件 skill 进注册表，再翻启用位；两步都只读本对象不持锁的字段。
            if (skillRegistry != null) {
                skillRegistry.rescan();
                skillRegistry.enableSkillsFromPlugin(pluginId);
            }
        }
        log.info("Plugin {} {}", pluginId, enabled ? "enabled" : "disabled");
    }

    /**
     * 在插件被扫描到之前先写入禁用名单——供在线安装使用。
     *
     * 与 setEnabled(false) 的区别：后者要求插件已注册（否则抛异常），而新装插件
     * 尚未 rescan。这里直接落名单，使随后的 loadPlugins() 跳过其 JAR，
     * 保证用户在广场点「启用」之前，插件代码一行都不会执行。
     */
    /**
     * 应用平台封禁列表：命中的已安装插件强制写入禁用名单。
     * 不删除文件——留给用户处置，避免误封导致数据丢失；但禁用不可撤销（见 setEnabled）。
     *
     * @param revoked pluginId -> 封禁原因
     * @return 本次新增被禁用的插件 id
     */
    public synchronized List<String> applyRevocations(Map<String, String> revoked) {
        revokedPluginIds.clear();
        revokedPluginIds.putAll(revoked);
        List<String> newlyDisabled = new ArrayList<>();
        for (Map.Entry<String, String> e : revoked.entrySet()) {
            boolean installed = plugins.stream().anyMatch(p -> Objects.equals(p.getId(), e.getKey()));
            if (installed && disabledPluginIds.add(e.getKey())) {
                newlyDisabled.add(e.getKey());
                log.warn("Plugin {} revoked by registry ({}), forced to disabled", e.getKey(), e.getValue());
            }
        }
        if (!newlyDisabled.isEmpty() && systemSettingService != null) {
            systemSettingService.set(DISABLED_KEY,
                    cn.hutool.json.JSONUtil.toJsonStr(new TreeSet<>(disabledPluginIds)));
        }
        return newlyDisabled;
    }

    /** 该插件是否被平台封禁；返回原因，未封禁返回 null（供管理页标红提示） */
    public String revokedReason(String pluginId) {
        return revokedPluginIds.get(pluginId);
    }

    public synchronized void markDisabledBeforeLoad(String pluginId) {
        disabledPluginIds.add(pluginId);
        if (systemSettingService != null) {
            systemSettingService.set(DISABLED_KEY,
                    cn.hutool.json.JSONUtil.toJsonStr(new TreeSet<>(disabledPluginIds)));
        }
    }

    /** 该插件的工具尚未注册时，按 manifest 补加载其 JAR（启用一个此前被禁用的插件时调用） */
    private void loadJarsIfAbsent(String pluginId) {
        boolean alreadyLoaded = toolToPluginId.containsValue(pluginId);
        if (alreadyLoaded) {
            return;
        }
        File pluginDir = pluginDirById.get(pluginId);
        PluginMetadata meta = plugins.stream()
                .filter(p -> Objects.equals(p.getId(), pluginId)).findFirst().orElse(null);
        if (pluginDir == null || meta == null || meta.getBackendJars() == null) {
            return;
        }
        for (String jarName : meta.getBackendJars()) {
            File jarFile = resolveBackendJar(pluginDir, jarName, pluginId);
            if (jarFile == null) {
                continue;
            }
            try {
                loadJar(jarFile, pluginId);
            } catch (Exception e) {
                log.error("Failed to load JAR {} for newly enabled plugin {}: {}",
                        jarName, pluginId, e.getMessage());
            }
        }
    }

    /** 工具所属的插件 id（内置工具不在此映射中，返回 null） */
    public String getPluginIdForTool(String toolName) {
        return toolToPluginId.get(toolName);
    }

    /** 已加载的插件元数据；未知 id 返回 null */
    public PluginMetadata getPlugin(String pluginId) {
        return plugins.stream()
                .filter(p -> Objects.equals(p.getId(), pluginId))
                .findFirst().orElse(null);
    }

    /** 插件所在目录；未知 id 返回 null（供 PluginWebController 定位 web/ 静态资源） */
    public File getPluginDir(String pluginId) {
        return pluginDirById.get(pluginId);
    }

    /**
     * 该插件是否带 Web 前端（frontendEntry 为校验通过的 {@code web/} 内相对路径）。
     * 绝对 URL 形态返回 false——那种插件不经 PluginWebController，也不走 postMessage 桥。
     */
    public boolean hasWebEntry(String pluginId) {
        PluginMetadata meta = getPlugin(pluginId);
        return meta != null && meta.getFrontendEntry() != null && !isAbsoluteUrl(meta.getFrontendEntry());
    }

    private static boolean isAbsoluteUrl(String entry) {
        String lower = entry.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 校验 manifest.frontendEntry（规范 v2.3）。
     *
     * <p>绝对 http(s) URL 原样保留（旧形态，宿主直接 iframe 打开，本服务不介入）。
     * 相对路径必须落在插件目录的 {@code web/} 之下且文件存在——否则**置空并记 WARN**，
     * 当作没有前端入口：宁可这个插件在左栏显示空面板，也不能让一个指到
     * {@code ../../} 的入口把插件目录之外的文件静态服务出去。
     */
    void validateFrontendEntry(File pluginDir, PluginMetadata meta) {
        String entry = meta.getFrontendEntry();
        if (entry == null || entry.isBlank()) {
            meta.setFrontendEntry(null);
            return;
        }
        entry = entry.trim();
        if (isAbsoluteUrl(entry)) {
            meta.setFrontendEntry(entry);
            return;
        }
        String normalized = entry.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (!normalized.startsWith("web/")) {
            log.warn("Plugin {} declares frontendEntry '{}' outside web/, treated as no web entry",
                    meta.getId(), entry);
            meta.setFrontendEntry(null);
            return;
        }
        File target = resolveWebFile(pluginDir, normalized.substring("web/".length()));
        if (target == null) {
            log.warn("Plugin {} declares frontendEntry '{}' but the file is missing or escapes web/, "
                    + "treated as no web entry", meta.getId(), entry);
            meta.setFrontendEntry(null);
            return;
        }
        meta.setFrontendEntry(normalized);
    }

    /**
     * 把 {@code web/} 之下的相对子路径解析成真实文件。
     *
     * <p>用 canonical path 判定目标必须位于 {@code <pluginDir>/web/} 正下方——同时挡掉
     * {@code ../} 穿越与符号链接绕行。这是 Web 插件静态服务的唯一定位入口。
     *
     * @param subPath 相对 {@code web/} 的路径，如 {@code index.html}、{@code assets/app.js}
     * @return 校验通过且存在的普通文件；非法 / 不存在 / 是目录时返回 null
     */
    public File resolveWebFile(File pluginDir, String subPath) {
        if (pluginDir == null || subPath == null || subPath.isBlank()) {
            return null;
        }
        try {
            File webRoot = new File(pluginDir, "web");
            File target = new File(webRoot, subPath);
            String base = webRoot.getCanonicalPath() + File.separator;
            if (!target.getCanonicalPath().startsWith(base)) {
                return null;
            }
            return target.isFile() ? target : null;
        } catch (IOException e) {
            log.warn("Plugin web path check failed for '{}': {}", subPath, e.getMessage());
            return null;
        }
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
        } finally {
            disabledStateRefreshedAt = System.currentTimeMillis();
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
                validateFrontendEntry(pluginDir, meta);
                plugins.add(meta);
                pluginDirById.put(meta.getId(), pluginDir);

                // 收集插件携带的 skill 目录（规范 v2.1）：交给 SkillRegistry 注册，本服务不解析 skill.yml
                if (meta.getSkills() != null) {
                    for (String skillSubdir : meta.getSkills()) {
                        File skillDir = new File(pluginDir, skillSubdir);
                        if (skillDir.isDirectory()) {
                            pluginSkillDirs.add(new PluginSkillDir(skillDir, meta.getId()));
                        } else {
                            log.warn("Plugin {} declares skill dir '{}' but it does not exist, skip",
                                    meta.getId(), skillSubdir);
                        }
                    }
                }

                // 被禁用的插件不加载其 JAR。
                //
                // 这一条是安全边界而不只是优化：JAR 一旦被 loadClass，静态初始化块与
                // 无参构造器就已经在宿主 JVM 里跑过了，此后再"禁用"只是把工具从 LLM
                // 可见列表里摘掉，代码早已执行。管理员点"禁用"的预期是"别运行它"，
                // 必须在加载前就拦住。（插件元数据仍然登记，管理页照常展示与启停。）
                if (!isEnabled(meta.getId())) {
                    log.info("Plugin {} is disabled, skip loading its JARs", meta.getId());
                    continue;
                }

                // Load associated JARs if any
                if (meta.getBackendJars() != null) {
                    for (String jarName : meta.getBackendJars()) {
                        File jarFile = resolveBackendJar(pluginDir, jarName, meta.getId());
                        if (jarFile != null) {
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
     * 解析 backendJars 条目为插件目录内的真实 JAR 文件。
     *
     * manifest 由插件作者提供，`"backendJars": ["../../evil.jar"]` 能指到插件目录之外——
     * 本地手放插件时危害有限，但在线分发一旦落地就是现成的路径逃逸口，故在此收口：
     * 用 canonical path 校验目标必须位于插件目录正下方（同时挡掉符号链接绕行）。
     *
     * @return 校验通过且存在的 JAR；非法或不存在时返回 null（记日志跳过，不中断其余插件）
     */
    File resolveBackendJar(File pluginDir, String jarName, String pluginId) {
        if (jarName == null || jarName.isBlank()) {
            return null;
        }
        try {
            File jarFile = new File(pluginDir, jarName);
            String base = pluginDir.getCanonicalPath() + File.separator;
            if (!jarFile.getCanonicalPath().startsWith(base)) {
                log.error("Plugin {} declares backendJar '{}' outside its own directory, refuse to load",
                        pluginId, jarName);
                return null;
            }
            if (!jarFile.isFile()) {
                // manifest 声明了 backendJars 但文件不在——解压不全/被手删/打包漏了都会走到这里。
                // 此前这条路径不打任何日志，调用方 `if (jarFile != null) loadJar(...)` 又没有
                // else 分支：插件照常出现在列表里、启停可用，就是 0 个工具，日志里搜插件 id
                // 与 jar 名全是空，排障无从下手。
                log.warn("Plugin {} declares backendJar '{}' but file does not exist: {}",
                        pluginId, jarName, jarFile);
                return null;
            }
            return jarFile;
        } catch (IOException e) {
            log.error("Plugin {} backendJar '{}' path check failed: {}", pluginId, jarName, e.getMessage());
            return null;
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
        // guide（规范 v2.5）：quickActions 里 label 与 prompt 缺一不可——没有 prompt 的按钮点了没反应，
        // 没有 label 的按钮画不出来；直接丢弃而不是整个 guide 作废。
        if (meta.getGuide() != null && meta.getGuide().getQuickActions() != null) {
            List<PluginQuickAction> valid = new ArrayList<>();
            for (PluginQuickAction a : meta.getGuide().getQuickActions()) {
                if (a != null && a.getLabel() != null && !a.getLabel().isBlank()
                        && a.getPrompt() != null && !a.getPrompt().isBlank()) {
                    valid.add(a);
                } else {
                    log.warn("Plugin {} guide.quickActions has an entry without label/prompt, ignored", meta.getId());
                }
            }
            meta.getGuide().setQuickActions(valid);
        }
        // packs（规范 v2.3）：id 必须过与 pack / 插件同一套正则，非法项丢弃并告警——
        // 这串字符会被拼进注册表 URL 与磁盘路径，不能放行任意输入。
        if (meta.getPacks() != null) {
            List<String> valid = new ArrayList<>();
            for (String packId : meta.getPacks()) {
                if (packId != null && PACK_ID.matcher(packId).matches()) {
                    valid.add(packId);
                } else {
                    log.warn("Plugin {} declares invalid pack id '{}', ignored", meta.getId(), packId);
                }
            }
            meta.setPacks(valid);
        }
        return meta;
    }

    private void loadJar(File jar, String pluginId) throws IOException, ClassNotFoundException {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, this.getClass().getClassLoader());
        loadedClassLoaders.add(loader);

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
                            injectHostIfAware(instance, pluginId);
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

    void registerToolObject(Object toolObject, String pluginId) {
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
