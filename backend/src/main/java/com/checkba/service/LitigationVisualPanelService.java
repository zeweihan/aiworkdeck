package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.LitigationVisualService;
import com.checkba.service.ai.tools.LitigationVisualTools;
import com.checkba.storage.ProjectStorageResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「诉讼可视化」面板的后端。
 *
 * <p>面板做三件对话做不了的事：
 * <ol>
 *   <li><b>把已出的图列出来。</b>对话是线性的，翻上去找图很难；面板给一个图廊。</li>
 *   <li><b>换风格不重新问模型。</b>出图时语义地图与产物同放，换风格 = 拿旧地图重画，
 *       内容一个字不会变。重新问模型既费钱，也可能因为它这次读得不一样而改了内容。</li>
 *   <li><b>把材料交给 AI 的那句话由服务端拼。</b>触发词必须原样出现在 prompt 文本里
 *       才能命中 skill 注入（pinnedSkillId 只裁工具、不注入 prompt，是记在案的地雷），
 *       交给前端拼容易漏。</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LitigationVisualPanelService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LitigationVisualPanelService.class);

    private static final Long AGENT_USER_ID = 10001L;

    private final ProjectFileRepository projectFileRepository;
    private final ProjectFileService projectFileService;
    private final ProjectStorageResolver storageResolver;
    private final LitigationVisualService litviz;
    private final com.checkba.service.ai.LitigationPngService pngService;

    /** 图廊里的一条。 */
    public record DiagramView(
            Long folderId,
            String name,
            Long svgFileId,
            Long pngFileId,
            Long mapFileId,
            String layout,
            String mode,
            boolean draft,
            List<String> formats,
            String updatedAt
    ) {}

    public Map<String, Object> status() {
        LitigationVisualService.Runtime rt = litviz.runtime();
        String reason = litviz.unavailableReason();
        Map<String, Object> out = new HashMap<>();
        out.put("available", reason == null);
        out.put("reason", reason == null ? "" : reason);
        out.put("python", rt.pythonVersion());
        // graphviz 只影响流程图一种布局。前端据此提示"这台机器画不了流程图"，
        // 而不是把整个功能说成不可用——六种布局照常能出。
        out.put("graphviz", rt.graphvizDir() != null || probeDotOnPath());
        return out;
    }

    private boolean probeDotOnPath() {
        LitigationVisualService.Result r = litviz.doctor();
        return r.ok() && r.raw().getBool("graphviz", false);
    }

    /**
     * 列出本项目里由诉讼可视化生成的图。
     *
     * <p>识别靠 wpsFileId 前缀（见 {@link LitigationVisualTools#MARKER_ARTIFACT}），
     * 不靠文件夹名或扩展名——用户完全可能自己往项目里放 svg，那些不该出现在图廊里。
     */
    public List<DiagramView> listDiagrams(Long projectId) {
        List<ProjectFile> all = projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId);
        // 按所属文件夹归拢：一图一文件夹是出图时的约定
        Map<Long, List<ProjectFile>> byFolder = new HashMap<>();
        for (ProjectFile f : all) {
            String wps = f.getWpsFileId();
            if (wps == null) continue;
            if (!wps.startsWith(LitigationVisualTools.MARKER_ARTIFACT)
                    && !wps.startsWith(LitigationVisualTools.MARKER_MAP)) continue;
            if (f.getParentId() == null) continue;
            byFolder.computeIfAbsent(f.getParentId(), k -> new ArrayList<>()).add(f);
        }

        List<DiagramView> out = new ArrayList<>();
        for (Map.Entry<Long, List<ProjectFile>> e : byFolder.entrySet()) {
            ProjectFile folder = projectFileRepository.findById(e.getKey()).orElse(null);
            if (folder == null) continue;

            Long svgId = null, pngId = null, mapId = null;
            List<String> formats = new ArrayList<>();
            String newest = null;
            for (ProjectFile f : e.getValue()) {
                String n = f.getName();
                if (n.endsWith(".map.json")) {
                    mapId = f.getId();
                } else if (n.endsWith(".drawio.svg")) {
                    formats.add("drawio");            // 带内嵌模型的那份不单独算一种格式
                } else if (n.endsWith(".svg")) {
                    svgId = f.getId();
                    formats.add("svg");
                } else if (n.endsWith(".png")) {
                    pngId = f.getId();
                    formats.add("png");
                } else {
                    int dot = n.lastIndexOf('.');
                    if (dot > 0) formats.add(n.substring(dot + 1));
                }
                String ts = f.getUpdatedAt() == null ? null : f.getUpdatedAt().toString();
                if (ts != null && (newest == null || ts.compareTo(newest) > 0)) newest = ts;
            }
            if (svgId == null) continue;              // 连母版都没有的不算一张图

            Map<String, String> meta = readMapMeta(mapId);
            out.add(new DiagramView(
                    folder.getId(), folder.getName(), svgId, pngId, mapId,
                    meta.getOrDefault("layout", ""), meta.getOrDefault("mode", ""),
                    folder.getName().endsWith("-draft"),
                    formats.stream().distinct().sorted().toList(),
                    newest));
        }
        out.sort(Comparator.comparing(DiagramView::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private Map<String, String> readMapMeta(Long mapFileId) {
        Map<String, String> out = new HashMap<>();
        if (mapFileId == null) return out;
        try {
            ProjectFile f = projectFileService.getFile(mapFileId);
            String body = Files.readString(storageResolver.resolve(f.getFilePath()), StandardCharsets.UTF_8);
            var json = cn.hutool.json.JSONUtil.parseObj(body);
            out.put("layout", json.getStr("layout", ""));
            out.put("mode", json.getStr("visual_mode", ""));
        } catch (Exception e) {
            log.debug("读取语义地图元信息失败 fileId={}", mapFileId, e);
        }
        return out;
    }

    /**
     * 用存下来的语义地图换一种视觉模式重画，原地替换同名产物。
     *
     * <p>内容不会变——同一份地图、同一套几何，只有表层不同。这正是"换风格"应该
     * 是一个按钮而不是一轮对话的原因。
     */
    public Map<String, Object> restyle(Long projectId, Long folderId, String mode) {
        String why = litviz.unavailableReason();
        if (why != null) throw new IllegalStateException(why);

        List<ProjectFile> siblings = projectFileRepository
                .findByProjectIdAndParentIdOrderBySortOrderAsc(projectId, folderId);
        ProjectFile mapFile = siblings.stream()
                .filter(f -> f.getName().endsWith(".map.json"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "这张图没有留下语义地图，换风格需要重新让 AI 出一次图"));

        Path work = null;
        try {
            work = Files.createTempDirectory("litviz-restyle-");
            Path map = work.resolve("map.json");
            Files.copy(storageResolver.resolve(mapFile.getFilePath()), map,
                    StandardCopyOption.REPLACE_EXISTING);

            String base = mapFile.getName().substring(0, mapFile.getName().length() - ".map.json".length());
            // 草稿图的 basename 本身就带 -draft，而地图里 confirmed 仍是 false——
            // 直接拿它当前缀，引擎会**再加一次**后缀变成 xxx-draft-draft，
            // 于是产物名与既有文件全对不上，五个文件被当成新文件重复登记一遍。
            // 先剥掉，让引擎按地图的真实状态自己决定加不加。
            if (base.endsWith("-draft")) {
                base = base.substring(0, base.length() - "-draft".length());
            }
            LitigationVisualService.Result r = litviz.render(map, work.resolve(base), mode, null);
            if (!r.ok()) throw new IllegalStateException("重画失败：" + r.error());

            // 原地替换：同名文件覆盖内容，文件 ID 不变——编辑器里已打开的标签会
            // 收到 reload 而不是变成一个孤儿标签。
            var files = r.raw().getJSONArray("files");
            // 与出图那条一致：引擎的 PNG 依赖外部光栅器（桌面端不带），服务端补上，
            // 否则换完风格这张图就插不回文书了。
            java.util.List<Path> paths = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                paths.add(Path.of(files.getJSONObject(i).getStr("path")));
            }
            for (Path extra : pngService.ensurePngFor(paths)) {
                files.add(cn.hutool.json.JSONUtil.createObj().set("path", extra.toString()));
            }
            int replaced = 0;
            for (int i = 0; i < files.size(); i++) {
                Path src = Path.of(files.getJSONObject(i).getStr("path"));
                String name = src.getFileName().toString();
                ProjectFile target = siblings.stream()
                        .filter(f -> name.equals(f.getName())).findFirst().orElse(null);
                if (target == null) {
                    // 这次多出来的格式（比如上次只出了 svg），补登记
                    target = projectFileService.createFile(projectId, folderId, name,
                            extOf(name), Files.size(src), null,
                            LitigationVisualTools.MARKER_ARTIFACT + projectId + "_" + System.currentTimeMillis(),
                            AGENT_USER_ID);
                }
                Path dest = storageResolver.resolve(target.getFilePath());
                Files.createDirectories(dest.getParent());
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                target.setFileSize(Files.size(dest));
                projectFileRepository.save(target);
                replaced++;
            }

            Map<String, Object> out = new HashMap<>();
            out.put("ok", true);
            out.put("mode", r.raw().getStr("mode", mode));
            out.put("replaced", replaced);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("重画失败：" + e.getMessage(), e);
        } finally {
            deleteTree(work);
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    /**
     * 拼「开始出图」那句话。
     *
     * <p><b>触发词必须原样出现在正文里</b>——skill 注入靠 SkillRouter 在用户消息里
     * 找关键词，pinnedSkillId 只裁剪工具不注入 prompt。这条是记在案的地雷，
     * 所以这句话由服务端拼、不交给前端。
     */
    public String buildKickoffPrompt(String scopeDescription, String diagramHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("诉讼可视化：");
        if (diagramHint != null && !diagramHint.isBlank()) {
            sb.append("请出一张").append(diagramHint.trim()).append("。\n");
        } else {
            sb.append("请先判断这批材料最适合画成哪一种图（时间轴 / 流程图 / 关系图），再出图。\n");
        }
        sb.append("材料范围：").append(
                scopeDescription == null || scopeDescription.isBlank() ? "本项目全部材料" : scopeDescription.trim());
        sb.append("\n\n请按流程来：先通读材料做抽取，写出语义地图；");
        sb.append("然后调 litigation_checkpoint 把三个确认问题原样发给我，等我回复；");
        sb.append("我确认后再出图。原文逐字保留，不要改动任何表述。");
        return sb.toString();
    }

    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
