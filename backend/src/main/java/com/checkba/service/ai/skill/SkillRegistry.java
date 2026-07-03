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

    private final SkillProperties properties;
    private final SystemSettingService systemSettingService;
    private final PluginService pluginService;

    /** id -> skill（保持扫描顺序，内置目录优先于插件） */
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /** 被禁用 skill id 集合（内存缓存，与 system_setting 同步） */
    private final Set<String> disabledSkillIds = ConcurrentHashMap.newKeySet();

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
     * Skill 是否可用于触发匹配：
     * 自身未被禁用，且（若来自插件）所属插件也处于启用状态。
     */
    public boolean isAvailable(SkillDefinition skill) {
        if (!isEnabled(skill.getId())) {
            return false;
        }
        return skill.getSourcePluginId() == null || pluginService.isEnabled(skill.getSourcePluginId());
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
        if (systemSettingService != null) {
            systemSettingService.set(DISABLED_KEY,
                    cn.hutool.json.JSONUtil.toJsonStr(new TreeSet<>(disabledSkillIds)));
        }
        log.info("Skill {} {}", skillId, enabled ? "enabled" : "disabled");
    }

    // ==================== 扫描与解析 ====================

    private void scan() {
        // 1. 内置 skills/ 目录
        File dir = new File(properties.getDir());
        if (dir.isDirectory()) {
            File[] skillDirs = dir.listFiles(File::isDirectory);
            if (skillDirs != null) {
                java.util.Arrays.sort(skillDirs);
                for (File skillDir : skillDirs) {
                    register(skillDir, null);
                }
            }
        } else {
            log.info("Skills dir '{}' not found, skip built-in skill scan", properties.getDir());
        }
        // 2. 插件携带的 skill 目录（manifest.skills，规范 v2.1）
        for (PluginService.PluginSkillDir psd : pluginService.getPluginSkillDirs()) {
            register(psd.dir(), psd.pluginId());
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
        if (systemSettingService == null) {
            return;
        }
        try {
            String json = systemSettingService.get(DISABLED_KEY, "[]");
            List<String> ids = cn.hutool.json.JSONUtil.toList(json, String.class);
            if (ids != null) {
                disabledSkillIds.addAll(ids.stream().filter(Objects::nonNull).toList());
            }
        } catch (Exception e) {
            log.error("Failed to load skill disabled state, default to all enabled", e);
        } finally {
            disabledStateRefreshedAt = System.currentTimeMillis();
        }
    }
}
