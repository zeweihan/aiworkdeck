package com.checkba.service.ai.skill;

import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.PluginService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 注册表（Phase 3B，规范见 docs/SKILL_SPEC.md）。
 *
 * 职责：
 * 1. 启动扫描 skills/ 目录（可配置 ai.skills.dir），解析每个子目录的 skill.yml + prompt 模板；
 * 2. 注册插件携带的 skill（PluginService 扫描 manifest.skills 收集目录，本类拉取注册）；
 * 3. 启停持久化（system_setting key = {@link #DISABLED_KEY}，做法同 ai.plugins.disabled）；
 * 4. 支持 rescan()；解析失败的 skill 跳过不阻断。
 *
 * 触发匹配与工具裁剪见 {@link SkillRouter}——本类只管"有哪些 skill、是否可用"。
 */
@Service
@Slf4j
public class SkillRegistry {

    /** system_setting 中存放被禁用 skill id 列表（JSON 数组）的 key */
    public static final String DISABLED_KEY = "ai.skills.disabled";

    /** system_setting 中存放"仅手动"skill id 列表（JSON 数组）的 key */
    public static final String MANUAL_KEY = "ai.skills.manual";

    /**
     * system_setting 中存放"已做过默认启停初始化"的 skill id 列表（JSON 数组）的 key。
     *
     * 只对 {@code enabled_by_default: false} 的 skill 起作用：第一次扫描到这样的 id 时
     * 把它加入 {@link #DISABLED_KEY} 并把 id 记进这里；之后每次扫描/重启都先查这个名单——
     * 已经记录过的 id 不再重复"打回默认关闭"，这样用户手动打开/关闭过的状态才不会被
     * 下一次启动悄悄覆盖。做法与 DISABLED_KEY / MANUAL_KEY 一致（同一套 readIdSet/persist）。
     */
    public static final String SEEDED_KEY = "ai.skills.seeded";

    private final SkillProperties properties;
    private final SystemSettingService systemSettingService;
    private final PluginService pluginService;

    /**
     * 应用语言（EN 版 PR5）：{@link #isAvailable} 按 skill.yml 的 languages 字段过滤。
     * 可空（部分单测/评测不接语言服务）——为 null 时按 zh-CN 语义，行为与引入前一致。
     */
    private final com.checkba.service.AppLanguageService appLanguageService;

    /** id -> skill（保持扫描顺序，内置目录优先于插件） */
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /** 被禁用 skill id 集合（内存缓存，与 system_setting 同步） */
    private final Set<String> disabledSkillIds = ConcurrentHashMap.newKeySet();

    /** "仅手动"skill id 集合：不参与触发词自动匹配，只能由用户在对话中钉选生效 */
    private final Set<String> manualSkillIds = ConcurrentHashMap.newKeySet();

    /** 已做过默认启停初始化的 skill id 集合（见 {@link #SEEDED_KEY}） */
    private final Set<String> seededSkillIds = ConcurrentHashMap.newKeySet();

    private volatile long disabledStateRefreshedAt = 0L;

    @Autowired
    public SkillRegistry(SkillProperties properties,
                         @Nullable SystemSettingService systemSettingService,
                         PluginService pluginService,
                         @Nullable com.checkba.service.AppLanguageService appLanguageService) {
        this.properties = properties;
        this.systemSettingService = systemSettingService;
        this.pluginService = pluginService;
        this.appLanguageService = appLanguageService;
    }

    /**
     * 「语音」合并插件的成员 skill（语音合成 + 会议录音，dev-board#66）。
     * 概念模型是「左栏一个图标 = 一个插件」：两者共占 rail 'voice' 一个面板位，
     * 广场里是一个条目、启停一体。前端开关一次翻全部成员；这里在每次扫描后
     * 再做一次状态收敛（任一启用 → 全部启用），把存量安装里「语音合成开、
     * 会议录音关」这类分裂态归一——否则会出现面板 tab 可见、但「生成纪要」的
     * kick-off prompt 永远命不中 meeting-recorder skill 的静默断裂。
     */
    static final List<String> VOICE_MERGED_SKILL_IDS = List.of("text-to-speech", "meeting-recorder");

    @PostConstruct
    public void init() {
        loadDisabledState();
        scan();
        convergeVoiceMergedSkills();
        log.info("SkillRegistry initialized: {} skills from dir '{}' (+plugins)",
                skills.size(), properties.getDir());
    }

    /** 重新扫描：清空后全量重载（含插件携带的 skill） */
    public synchronized void rescan() {
        skills.clear();
        loadDisabledState();
        scan();
        convergeVoiceMergedSkills();
        log.info("Skill rescan done: {} skills", skills.size());
    }

    /** 见 {@link #VOICE_MERGED_SKILL_IDS}：任一成员启用即全部启用；全关保持全关。 */
    private void convergeVoiceMergedSkills() {
        if (!skills.keySet().containsAll(VOICE_MERGED_SKILL_IDS)) {
            return; // 部署形态里缺成员目录时不收敛，别把半套安装的状态改来改去
        }
        boolean anyEnabled = VOICE_MERGED_SKILL_IDS.stream().anyMatch(id -> !disabledSkillIds.contains(id));
        if (!anyEnabled) {
            return;
        }
        if (disabledSkillIds.removeAll(VOICE_MERGED_SKILL_IDS)) {
            persist(DISABLED_KEY, disabledSkillIds);
            log.info("Voice merged skills converged to enabled: {}", VOICE_MERGED_SKILL_IDS);
        }
    }

    /** 全部已注册 skill（含被禁用的，供管理页展示） */
    public synchronized List<SkillDefinition> getSkills() {
        return new ArrayList<>(skills.values());
    }

    public synchronized Optional<SkillDefinition> getSkill(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    /**
     * Skill 是否可用（可被钉选或自动匹配）：
     * 自身未被禁用，且（若来自插件）所属插件也处于启用状态。
     *
     * 注意"仅手动"的 skill 依然 available——它只是不参与自动匹配，
     * 用户钉选时仍要能生效。自动匹配的额外过滤见 {@link SkillRouter#match}。
     */
    public boolean isAvailable(SkillDefinition skill) {
        if (!supportsCurrentLanguage(skill)) {
            return false;
        }
        if (!isEnabled(skill.getId())) {
            return false;
        }
        return skill.getSourcePluginId() == null || pluginService.isEnabled(skill.getSourcePluginId());
    }

    /**
     * 语言过滤（EN 版 PR5）：skill.yml 的 languages 列表声明可用的应用语言，
     * **缺省（空）= 只在 zh-CN 可用**——存量 skill 没有该字段，英文版下自动隐藏，方向安全
     * （如 listing-pathway 的触发词含 IPO/SPAC/VIE，会命中英文输入，必须真隐藏）。
     * 收口选在 isAvailable：match（自动匹配）、activateForTurn（钉选）、activeSkill（注入前复查）
     * 三条路径都过它，不会出现"列表过滤了、注入没过滤"的缝。
     * zh-CN 下空列表恒可用，行为与引入前逐字节一致。
     */
    private boolean supportsCurrentLanguage(SkillDefinition skill) {
        String lang = appLanguageService != null
                ? appLanguageService.language()
                : com.checkba.service.AppLanguageService.ZH_CN;
        List<String> langs = skill.getLanguages();
        if (langs == null || langs.isEmpty()) {
            return com.checkba.service.AppLanguageService.ZH_CN.equals(lang);
        }
        return langs.contains(lang);
    }

    /** skill 是否为"仅手动"（不参与触发词自动匹配） */
    public boolean isManual(String skillId) {
        maybeRefreshDisabledState();
        return manualSkillIds.contains(skillId);
    }

    /**
     * 生效方式三档（对外呈现用；内部由 disabled / manual 两个正交名单组合得出）。
     * disabled 优先：被禁用的 skill 无论是否在 manual 名单里都报 DISABLED。
     */
    public ActivationMode activationMode(String skillId) {
        if (!isEnabled(skillId)) {
            return ActivationMode.DISABLED;
        }
        return isManual(skillId) ? ActivationMode.MANUAL : ActivationMode.AUTO;
    }

    /**
     * 设置生效方式（三档的唯一写入口）。
     * @throws IllegalArgumentException skillId 不在已注册列表中
     */
    public synchronized void setActivationMode(String skillId, ActivationMode mode) {
        if (!skills.containsKey(skillId)) {
            throw new IllegalArgumentException("Unknown skill: " + skillId);
        }
        if (mode == ActivationMode.DISABLED) {
            disabledSkillIds.add(skillId);
        } else {
            disabledSkillIds.remove(skillId);
        }
        if (mode == ActivationMode.MANUAL) {
            manualSkillIds.add(skillId);
        } else {
            manualSkillIds.remove(skillId);
        }
        persist(DISABLED_KEY, disabledSkillIds);
        persist(MANUAL_KEY, manualSkillIds);
        log.info("Skill {} activation mode -> {}", skillId, mode);
    }

    /** Skill 生效方式 */
    public enum ActivationMode {
        /** 命中触发词时自动生效（默认） */
        AUTO,
        /** 只能由用户在对话中钉选生效，不参与自动匹配 */
        MANUAL,
        /** 停用 */
        DISABLED;

        /** 解析前端传来的字符串，无法识别时返回 empty */
        public static Optional<ActivationMode> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** skill 是否启用（默认启用，禁用名单持久化在 system_setting，带 TTL 缓存） */
    public boolean isEnabled(String skillId) {
        maybeRefreshDisabledState();
        return !disabledSkillIds.contains(skillId);
    }

    /**
     * 启用 / 禁用 skill 并持久化。
     * @throws IllegalArgumentException skillId 不在已注册列表中
     */
    public synchronized void setEnabled(String skillId, boolean enabled) {
        if (!skills.containsKey(skillId)) {
            throw new IllegalArgumentException("Unknown skill: " + skillId);
        }
        if (enabled) {
            disabledSkillIds.remove(skillId);
        } else {
            disabledSkillIds.add(skillId);
        }
        persist(DISABLED_KEY, disabledSkillIds);
        log.info("Skill {} {}", skillId, enabled ? "enabled" : "disabled");
    }

    private void persist(String key, Set<String> ids) {
        if (systemSettingService != null) {
            systemSettingService.set(key, cn.hutool.json.JSONUtil.toJsonStr(new TreeSet<>(ids)));
        }
    }

    // ==================== 扫描与解析 ====================

    private void scan() {
        // 1. 可写目录（广场安装落点）。**先扫它**：id 去重是"先扫到优先"，
        //    这样广场装的同 id skill 能覆盖随发行版分发的那份——内置 skill 出了问题
        //    可以走广场热修，不必等下一个客户端版本。被盖住的会打日志。
        scanSkillDir(new File(properties.getDir()), "writable");
        // 2. 随发行版分发的只读内置目录（打包态由 AI_SKILLS_BUILTIN_DIR 注入）。
        //    dev 态留空——那时内置 skill 就在上面那个相对目录里。
        String builtin = properties.getBuiltinDir();
        if (builtin != null && !builtin.isBlank()) {
            File builtinDir = new File(builtin);
            // 两处配到同一个目录时别扫两遍（否则每个内置 skill 都要报一次重复告警）。
            // 必须比 canonical 路径：一边可能是相对路径、一边绝对路径，File.equals 只做字面比较。
            if (!sameDir(builtinDir, new File(properties.getDir()))) {
                scanSkillDir(builtinDir, "builtin");
            }
        }
        // 3. 插件携带的 skill 目录（manifest.skills，规范 v2.1）
        for (PluginService.PluginSkillDir psd : pluginService.getPluginSkillDirs()) {
            register(psd.dir(), psd.pluginId());
        }
    }

    private static boolean sameDir(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (java.io.IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    private void scanSkillDir(File dir, String label) {
        if (!dir.isDirectory()) {
            log.info("Skills dir '{}' ({}) not found, skip", dir.getPath(), label);
            return;
        }
        File[] skillDirs = dir.listFiles(File::isDirectory);
        if (skillDirs == null) return;
        java.util.Arrays.sort(skillDirs);
        for (File skillDir : skillDirs) {
            register(skillDir, null);
        }
    }

    /** 注册一个 skill 目录；解析失败跳过不阻断 */
    private void register(File skillDir, String sourcePluginId) {
        try {
            SkillDefinition skill = parseSkillDir(skillDir);
            if (skill == null) {
                return;
            }
            skill.setSourcePluginId(sourcePluginId);
            SkillDefinition previous = skills.putIfAbsent(skill.getId(), skill);
            if (previous != null) {
                log.warn("Duplicate skill id '{}', skip dir: {}", skill.getId(), skillDir);
            } else {
                seedDefaultDisabledIfNeeded(skill);
                log.info("Registered skill '{}' ({} triggers, {} allowed tools{})",
                        skill.getId(), skill.getTriggers().size(), skill.getAllowedTools().size(),
                        sourcePluginId != null ? ", from plugin " + sourcePluginId : "");
            }
        } catch (Exception e) {
            log.error("Failed to load skill dir {}, skip: {}", skillDir, e.getMessage());
        }
    }

    /**
     * skill.yml 声明 {@code enabled_by_default: false} 且这个 id **从未被种子化过**时，
     * 把它加入禁用名单并把 id 记入 {@link #SEEDED_KEY}。
     *
     * 种子化只发生一次：下次扫描（rescan / 重启）时 {@link #seededSkillIds} 已经从持久化
     * 存储里重新加载，命中即跳过——用户之后手动开/关过的状态不会被这条逻辑打回去。
     * 未持久化场景（systemSettingService 为 null，如部分单测）里 seededSkillIds 每次都是空的，
     * 这条逻辑退化为"每次扫描都按默认关闭初始化"，与"从未种过"语义一致，不算特例。
     */
    private void seedDefaultDisabledIfNeeded(SkillDefinition skill) {
        if (skill.isEnabledByDefault() || seededSkillIds.contains(skill.getId())) {
            return;
        }
        disabledSkillIds.add(skill.getId());
        seededSkillIds.add(skill.getId());
        persist(DISABLED_KEY, disabledSkillIds);
        persist(SEEDED_KEY, seededSkillIds);
        log.info("Skill '{}' seeded as disabled (enabled_by_default: false, first time seen)", skill.getId());
    }

    /**
     * 解析一个 skill 目录（skill.yml + prompt 模板）。
     * @return 解析后的定义；skill.yml 缺失或必填字段缺失返回 null（记录日志）
     */
    SkillDefinition parseSkillDir(File skillDir) throws Exception {
        File yml = new File(skillDir, "skill.yml");
        if (!yml.isFile()) {
            log.warn("No skill.yml in {}, skip", skillDir);
            return null;
        }
        Map<String, Object> raw;
        try (var in = Files.newInputStream(yml.toPath())) {
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map)) {
                log.error("skill.yml in {} is not a mapping, skip", skillDir);
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) parsed;
            raw = cast;
        }

        SkillDefinition skill = new SkillDefinition();
        skill.setId(asString(raw.get("id")));
        skill.setName(asString(raw.get("name")));
        skill.setDescription(asString(raw.get("description")));
        skill.setTriggers(asStringList(raw.get("triggers")));
        skill.setAllowedTools(asStringList(raw.get("allowed_tools")));
        skill.setOutput(asString(raw.get("output")));
        skill.setRequires(asStringList(raw.get("requires")));
        skill.setCategory(asString(raw.get("category")));
        skill.setAuthor(asString(raw.get("author")));
        skill.setAuthorUrl(asString(raw.get("author_url")));
        skill.setVersion(asString(raw.get("version")));
        skill.setLicense(asString(raw.get("license")));
        skill.setCredits(asStringList(raw.get("credits")));
        // 依赖的原生资源包（规范 docs/NATIVE_PACK_DISTRIBUTION.md §7.1，可选）
        skill.setRequiresPack(asString(raw.get("requires_pack")));
        // 应用语言相关的可选字段（EN 版 PR5）：缺省时全部为空，语义 = 只在 zh-CN 可用
        skill.setLanguages(asStringList(raw.get("languages")));
        skill.setNameEn(asString(raw.get("name_en")));
        skill.setTriggersEn(asStringList(raw.get("triggers_en")));
        skill.setOutputEn(asString(raw.get("output_en")));
        Boolean enabledByDefault = asBoolean(raw.get("enabled_by_default"));
        if (enabledByDefault != null) {
            skill.setEnabledByDefault(enabledByDefault);
        }
        String promptFile = asString(raw.get("prompt"));
        if (promptFile != null && !promptFile.isBlank()) {
            skill.setPromptFile(promptFile);
        }

        if (skill.getId() == null || skill.getId().isBlank()) {
            log.error("skill.yml in {} missing required 'id', skip", skillDir);
            return null;
        }
        if (skill.getTriggers().isEmpty()) {
            log.error("Skill '{}' has no triggers, it can never be matched, skip", skill.getId());
            return null;
        }

        File prompt = new File(skillDir, skill.getPromptFile());
        if (!prompt.isFile()) {
            log.error("Skill '{}' prompt file '{}' not found in {}, skip",
                    skill.getId(), skill.getPromptFile(), skillDir);
            return null;
        }
        skill.setPromptTemplate(Files.readString(prompt.toPath(), StandardCharsets.UTF_8));

        // 英文 prompt 模板（约定文件名 prompt.en.md，可选）：存在即加载，英文模式注入时优先用它。
        // 扫描期两版都读进内存——语言是运行期设置，切换语言不需要 rescan。
        File promptEn = new File(skillDir, "prompt.en.md");
        if (promptEn.isFile()) {
            skill.setPromptTemplateEn(Files.readString(promptEn.toPath(), StandardCharsets.UTF_8));
        }
        return skill;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** SnakeYAML 对 true/false 字面量已经解析成 Boolean；容错处理字符串写法 ("false"/"true") */
    private static Boolean asBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<String> asStringList(Object v) {
        List<String> result = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    // ==================== 启停状态（做法同 PluginService） ====================

    private void maybeRefreshDisabledState() {
        if (systemSettingService == null) {
            return;
        }
        if (System.currentTimeMillis() - disabledStateRefreshedAt < properties.getDisabledCacheTtlMs()) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - disabledStateRefreshedAt < properties.getDisabledCacheTtlMs()) {
                return;
            }
            loadDisabledState();
        }
    }

    private void loadDisabledState() {
        disabledSkillIds.clear();
        manualSkillIds.clear();
        seededSkillIds.clear();
        if (systemSettingService == null) {
            return;
        }
        try {
            disabledSkillIds.addAll(readIdSet(DISABLED_KEY));
            manualSkillIds.addAll(readIdSet(MANUAL_KEY));
            seededSkillIds.addAll(readIdSet(SEEDED_KEY));
        } catch (Exception e) {
            log.error("Failed to load skill activation state, default to all auto", e);
        } finally {
            disabledStateRefreshedAt = System.currentTimeMillis();
        }
    }

    private List<String> readIdSet(String key) {
        String json = systemSettingService.get(key, "[]");
        List<String> ids = cn.hutool.json.JSONUtil.toList(json, String.class);
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
    }
}
