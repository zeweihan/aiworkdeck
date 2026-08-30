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

    public StyleProfile resolve(Long projectId, String explicitJson) {
        StyleProfile house = StyleProfiles.houseDefault();
        if (explicitJson != null && !explicitJson.isBlank()) {
            try {
                return house.merge(StyleProfiles.parse(explicitJson));
            } catch (Exception e) {
                log.warn("styleProfileJson 解析失败，退到项目画像: {}", e.getMessage());
            }
        }
        if (projectId != null) {
            try {
                String json = readProjectProfile(projectId);
                if (json != null) return house.merge(StyleProfiles.parse(json));
            } catch (Exception e) {
                log.warn("项目 {} 的 {}/{} 读取失败，退到系统默认: {}", projectId, TEMPLATE_FOLDER, PROFILE_FILE, e.getMessage());
            }
        }
        // 插件贡献画像（规范 v2.9 P4）：用户选中的才生效；不可用时 selectedStyleProfileJson
        // 已自带 WARN 并返回 null，这里静默退下一级
        if (pluginContributionService != null) {
            try {
                String json = pluginContributionService.selectedStyleProfileJson();
                if (json != null) return house.merge(StyleProfiles.parse(json));
            } catch (Exception e) {
                log.warn("插件画像解析失败，退到系统默认: {}", e.getMessage());
            }
        }
        try {
            String json = systemSettingService == null ? null : systemSettingService.get(SETTING_KEY, null);
            if (json != null && !json.isBlank()) return house.merge(StyleProfiles.parse(json));
        } catch (Exception e) {
            log.warn("SystemSetting {} 解析失败，退到 house-default: {}", SETTING_KEY, e.getMessage());
        }
        return house;
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
