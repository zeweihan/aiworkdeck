// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfile;
import com.checkba.util.style.StyleProfiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 写端画像解析顺序（spec §3.4）：工具显式 styleProfileJson &gt; 项目 {@code _模板/画像.json}
 * &gt; SystemSetting {@code dd.styleProfile.default} &gt; house-default。
 *
 * <p>选中的画像总是 merge 到 house-default 之上：画像里缺省的叶子（比如只学到标题没学到表格）
 * 由 HOUSE 补齐，写端永远拿到完整画像。任何一级解析失败只记 warn 并退到下一级，不让导出整个失败。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StyleProfileResolver {

    public static final String TEMPLATE_FOLDER = "_模板";
    public static final String PROFILE_FILE = "画像.json";
    public static final String SETTING_KEY = "dd.styleProfile.default";

    private final ProjectFileRepository projectFileRepository;
    private final StorageServiceFactory storageServiceFactory;
    private final SystemSettingService systemSettingService;

    /**
     * 插件贡献画像（规范 v2.9 P4）：用户显式选中的插件画像插在「项目画像」与「系统默认」
     * 之间。可选注入（@Lazy + required=false）：直接 new 的既有测试停留 null，判空跳过。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private PluginContributionService pluginContributionService;

    /** 供测试直接装配。 */
    void setPluginContributionService(PluginContributionService svc) {
        this.pluginContributionService = svc;
    }

    /**
     * 选中的画像来自哪一级（dev-board#729 ②）。
     *
     * <p>模型此前无从判断「这个项目到底有没有模板画像」——工具描述只说「项目有模板画像时用本工具」，
     * 于是真机上它会先花一整轮去 {@code list_files(_模板)} 探一探。事实直接写进末位提醒就没有这一轮。
     */
    public enum Source {
        /** 工具调用显式传了 styleProfileJson */
        EXPLICIT("本次调用显式传入"),
        /** 项目 _模板/画像.json */
        PROJECT("项目 " + TEMPLATE_FOLDER + "/" + PROFILE_FILE),
        /** 用户选中的插件贡献画像 */
        PLUGIN("已选中的插件画像"),
        /** SystemSetting dd.styleProfile.default */
        SYSTEM("系统默认画像设置"),
        /** 谁都没有，退到内置律所标准格式 */
        HOUSE_DEFAULT("无（内置律所标准格式）");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** 给模型看的来源说明（中文）。 */
        public String label() {
            return label;
        }

        /** 是否存在一份「团队自己的」画像（house-default 不算）。 */
        public boolean hasCustomProfile() {
            return this != HOUSE_DEFAULT;
        }
    }

    /** 画像 + 它来自哪一级。 */
    public record Resolved(StyleProfile profile, Source source) {
    }

    public StyleProfile resolve(Long projectId, String explicitJson) {
        return resolveWithSource(projectId, explicitJson).profile();
    }

    /**
     * 与 {@link #resolve} 同一条解析链，额外回报命中的是哪一级。
     *
     * <p><b>解析链只此一份</b>：另写一个「只判断有没有画像」的函数，早晚会和真正的写端解析
     * 各说各话——末位提醒说「有画像」而写端退回了 house-default，两边都不报错。
     */
    public Resolved resolveWithSource(Long projectId, String explicitJson) {
        StyleProfile house = StyleProfiles.houseDefault();
        if (explicitJson != null && !explicitJson.isBlank()) {
            try {
                return new Resolved(house.merge(StyleProfiles.parse(explicitJson)), Source.EXPLICIT);
            } catch (Exception e) {
                log.warn("styleProfileJson 解析失败，退到项目画像: {}", e.getMessage());
            }
        }
        if (projectId != null) {
            try {
                String json = readProjectProfile(projectId);
                if (json != null) return new Resolved(house.merge(StyleProfiles.parse(json)), Source.PROJECT);
            } catch (Exception e) {
                log.warn("项目 {} 的 {}/{} 读取失败，退到系统默认: {}", projectId, TEMPLATE_FOLDER, PROFILE_FILE, e.getMessage());
            }
        }
        // 插件贡献画像（规范 v2.9 P4）：用户选中的才生效；不可用时 selectedStyleProfileJson
        // 已自带 WARN 并返回 null，这里静默退下一级
        if (pluginContributionService != null) {
            try {
                String json = pluginContributionService.selectedStyleProfileJson();
                if (json != null) return new Resolved(house.merge(StyleProfiles.parse(json)), Source.PLUGIN);
            } catch (Exception e) {
                log.warn("插件画像解析失败，退到系统默认: {}", e.getMessage());
            }
        }
        try {
            String json = systemSettingService == null ? null : systemSettingService.get(SETTING_KEY, null);
            if (json != null && !json.isBlank()) {
                return new Resolved(house.merge(StyleProfiles.parse(json)), Source.SYSTEM);
            }
        } catch (Exception e) {
            log.warn("SystemSetting {} 解析失败，退到 house-default: {}", SETTING_KEY, e.getMessage());
        }
        return new Resolved(house, Source.HOUSE_DEFAULT);
    }

    /** 项目根目录下 _模板/画像.json 的内容；没有返回 null。 */
    String readProjectProfile(Long projectId) throws Exception {
        if (projectFileRepository == null || storageServiceFactory == null) return null;
        List<ProjectFile> roots = projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, null);
        ProjectFile folder = null;
        for (ProjectFile f : roots) {
            boolean isDir = Boolean.TRUE.equals(f.getIsFolder()) || "folder".equalsIgnoreCase(f.getFileType());
            if (isDir && TEMPLATE_FOLDER.equals(f.getName())) { folder = f; break; }
        }
        if (folder == null) return null;
        List<ProjectFile> children = projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, folder.getId());
        for (ProjectFile c : children) {
            if (!PROFILE_FILE.equals(c.getName()) || c.getFilePath() == null) continue;
            Resource res = storageServiceFactory.getStorageService().load(c.getFilePath());
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
