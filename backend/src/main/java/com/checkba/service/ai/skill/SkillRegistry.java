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

    private final SkillProperties properties;
    private final SystemSettingService systemSettingService;
    private final PluginService pluginService;

    /** id -> skill（保持扫描顺序，内置目录优先于插件） */
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /** 被禁用 skill id 集合（内存缓存，与 system_setting 同步） */
    private final Set<String> disabledSkillIds = ConcurrentHashMap.newKeySet();

    /** "仅手动"skill id 集合：不参与触发词自动匹配，只能由用户在对话中钉选生效 */
    private final Set<String> manualSkillIds = ConcurrentHashMap.newKeySet();

    private volatile long disabledStateRefreshedAt = 0L;

    @Autowired
    public SkillRegistry(SkillProperties properties,
                         @Nullable SystemSettingService systemSettingService,
                         PluginService pluginService) {
        this.properties = properties;
        this.systemSettingService = systemSettingService;
        this.pluginService = pluginService;
    }

    @PostConstruct
    public void init() {
        loadDisabledState();
        scan();
        log.info("SkillRegistry initialized: {} skills from dir '{}' (+plugins)",
                skills.size(), properties.getDir());
    }

    /** 重新扫描：清空后全量重载（含插件携带的 skill） */
    public synchronized void rescan() {
        skills.clear();
        loadDisabledState();
        scan();
        log.info("Skill rescan done: {} skills", skills.size());
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
        if (!isEnabled(skill.getId())) {
            return false;
        }
        return skill.getSourcePluginId() == null || pluginService.isEnabled(skill.getSourcePluginId());
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
                log.info("Registered skill '{}' ({} triggers, {} allowed tools{})",
                        skill.getId(), skill.getTriggers().size(), skill.getAllowedTools().size(),
                        sourcePluginId != null ? ", from plugin " + sourcePluginId : "");
            }
        } catch (Exception e) {
            log.error("Failed to load skill dir {}, skip: {}", skillDir, e.getMessage());
        }
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
        return skill;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
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
        if (systemSettingService == null) {
            return;
        }
        try {
            disabledSkillIds.addAll(readIdSet(DISABLED_KEY));
            manualSkillIds.addAll(readIdSet(MANUAL_KEY));
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
