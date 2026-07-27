package com.checkba.service.ai.skill;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 在线 Skill 广场客户端（官网公共注册表，配置 ai.skills.registry-url）。
 *
 * 注册表契约：
 * - GET {registryUrl}                返回 skill 元数据 JSON 数组；
 * - GET {registryUrl}/{id}/bundle    返回 { id, version, files: { "skill.yml": ..., "prompt.md": ... } }。
 *
 * 安装 = 把 bundle 中的两个已知文件写入 {ai.skills.dir}/{id}/ 后 rescan（重装即更新）；
 * 注册表不可达只影响广场功能本身，不影响本地 skill / 插件体系。
 */
@Service
@Slf4j
public class SkillMarketService {

    /** skill id 白名单（kebab-case），同时兼作路径穿越防护 */
    private static final Pattern SKILL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    /** 允许从 bundle 落盘的文件名——只认这两个已知文件，其余条目一律忽略 */
    private static final List<String> BUNDLE_FILES = List.of("skill.yml", "prompt.md");

    private final SkillProperties properties;
    private final SkillRegistry skillRegistry;

    public SkillMarketService(SkillProperties properties, SkillRegistry skillRegistry) {
        this.properties = properties;
        this.skillRegistry = skillRegistry;
    }

    /** 广场 skill 视图：注册表元数据 + 本地是否已安装 */
    @lombok.Data
    public static class MarketSkillView {
        private String id;
        private String name;
        private String description;
        private String icon;
        private String version;
        private String author;
        private String authorDisplayName;
        private List<String> triggers = new ArrayList<>();
        private List<String> allowedTools = new ArrayList<>();
        /** 官网 SKILL_CATEGORIES 分类 id；旧注册表可能缺失，前端按"其他"兜底 */
        private String category;
        private Integer downloads;
        private String updatedAt;
        private String homepage;
        /** 本地 SkillRegistry 中已存在同 id skill */
        private boolean installed;
    }

    /**
     * 拉取在线广场列表并标注安装状态。
     * @throws IllegalStateException 注册表不可达或返回内容无法解析（调用方转 {code:1}，不阻断本地功能）
     */
    public List<MarketSkillView> listMarket() {
        String body = httpGet(properties.getRegistryUrl());
        List<MarketSkillView> list;
        try {
            list = JSONUtil.toList(JSONUtil.parseArray(body), MarketSkillView.class);
        } catch (Exception e) {
            throw new IllegalStateException("注册表返回内容无法解析: " + e.getMessage());
        }
        for (MarketSkillView view : list) {
            view.setInstalled(view.getId() != null && skillRegistry.getSkill(view.getId()).isPresent());
        }
        return list;
    }

    /**
     * 安装（或重装 = 更新）一个在线 skill：下载 bundle 写入 {ai.skills.dir}/{id}/ 并 rescan。
     * @return 安装的 skill id
     * @throws IllegalArgumentException id 非法
     * @throws IllegalStateException 注册表不可达 / bundle 缺必需文件 / 写盘失败
     */
    public synchronized String install(String id) {
        requireValidId(id);
        String body = httpGet(properties.getRegistryUrl() + "/" + id + "/bundle");
        JSONObject files;
        try {
            files = JSONUtil.parseObj(body).getJSONObject("files");
        } catch (Exception e) {
            throw new IllegalStateException("bundle 内容无法解析: " + e.getMessage());
        }
        for (String name : BUNDLE_FILES) {
            if (files == null || !(files.get(name) instanceof CharSequence)) {
                throw new IllegalStateException("bundle 缺少必需文件: " + name);
            }
        }
        try {
            File skillDir = new File(properties.getDir(), id);
            Files.createDirectories(skillDir.toPath());
            for (String name : BUNDLE_FILES) {
                Files.writeString(new File(skillDir, name).toPath(), files.getStr(name), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入 skill 文件失败: " + e.getMessage());
        }
        skillRegistry.rescan();
        log.info("Installed market skill '{}'", id);
        return id;
    }

    /**
     * 卸载在线安装的 skill：删除 {ai.skills.dir}/{id}/ 目录并 rescan。
     * @throws IllegalArgumentException id 非法或未安装
     * @throws IllegalStateException skill 来自插件（应通过插件管理卸载）或删除失败
     */
    public synchronized void uninstall(String id) {
        requireValidId(id);
        SkillDefinition existing = skillRegistry.getSkill(id).orElse(null);
        if (existing != null && existing.getSourcePluginId() != null) {
            throw new IllegalStateException("该 Skill 来自插件 " + existing.getSourcePluginId() + "，请通过插件管理卸载");
        }
        try {
            File skillsDir = new File(properties.getDir());
            File skillDir = new File(skillsDir, id);
            // 只允许删除 skills 目录正下方的子目录（canonical 校验防符号链接逃逸）
            if (!skillDir.getCanonicalPath().startsWith(skillsDir.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("非法 skill 目录: " + id);
            }
            if (!skillDir.isDirectory()) {
                throw new IllegalArgumentException("Skill 未安装: " + id);
            }
            FileUtil.del(skillDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("删除 skill 目录失败: " + e.getMessage());
        }
        skillRegistry.rescan();
        log.info("Uninstalled market skill '{}'", id);
    }

    private static void requireValidId(String id) {
        if (id == null || !SKILL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 skill id: " + id);
        }
    }

    /** HTTP GET（单测覆写此 seam 打桩）；非 200 或网络异常抛 IllegalStateException */
    protected String httpGet(String url) {
        HttpResponse resp;
        try {
            resp = HttpRequest.get(url)
                    .setConnectionTimeout(5000)
                    .setReadTimeout(10000)
                    .execute();
        } catch (Exception e) {
            throw new IllegalStateException("注册表不可达: " + e.getMessage());
        }
        if (resp.getStatus() != 200) {
            throw new IllegalStateException("注册表请求失败 (HTTP " + resp.getStatus() + ")");
        }
        return resp.body();
    }
}
