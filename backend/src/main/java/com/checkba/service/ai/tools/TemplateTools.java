package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.DocxProfileReader;
import com.checkba.util.style.StyleProfile;
import com.checkba.util.style.StyleProfiles;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模板画像工具（尽调 P1，spec docs/superpowers/specs/2026-08-21-dd-p1-drafting-design.md §3）。
 *
 * <p>{@code docx_inspect_template}：docx4j 直读团队模板的 styles/numbering/document/sectPr/页眉页脚，
 * 产出 styleProfile v1 JSON。多份取众数并给置信度。{@code .doc} 老格式不报错，只提示另存或在编辑器里学习
 * （LOWA 兜底在 P3）。
 *
 * <p>fileId 是 LLM 自由填写的业务 ID，用 {@link ToolFileGuard} 校验归属。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TemplateTools implements AgentToolComponent {

    static final String DOC_HINT = "是 .doc 老格式，读不了样式：请先另存为 .docx，或在编辑器打开后再学习。";

    /** 落盘画像用的写入者（与 write_file / write_docx 同一个 Agent 身份）。 */
    private static final Long AGENT_USER_ID = 10001L;

    private final ProjectFileRepository projectFileRepository;
    private final StorageServiceFactory storageServiceFactory;
    /** 落盘 _模板/画像.json 用；单测里可为 null（只学不存）。 */
    private final ProjectFileService projectFileService;

    @ToolMeta(displayName = "学习模板格式", category = "file")
    @Tool("Learn the formatting profile (styleProfile v1 JSON) of one or more team template .docx files: body/heading "
            + "fonts (eastAsia + western), sizes, alignment, spacing, line spacing, first-line indent, heading numbering "
            + "(auto numPr vs literal text like 一、（一）1.), table borders (table vs cell level), column widths, header row, "
            + "page setup, header/footer page-number pattern, TOC field. Pass the database fileIds of the templates "
            + "(comma-separated for several; the majority value wins and a confidence is reported). The profile is saved "
            + "automatically as the project's _模板/画像.json (that is the file doc_apply_style_profile and write_docx read), "
            + "so you never need to write it yourself; the returned JSON carries savedProfileFileId/savedProfilePath.")
    public String docx_inspect_template(
            @P("Template file database IDs, comma-separated, e.g. \"123\" or \"123,456\"") String fileIds,
            @P(value = "Optional JSON options, e.g. {\"name\":\"某律所尽调报告模板\"}", required = false) String options
    ) {
        log.info("Tool: docx_inspect_template fileIds={}", fileIds);
        List<Long> ids = parseIds(fileIds);
        if (ids.isEmpty()) {
            return "Error: fileIds is required (comma-separated database file IDs).";
        }
        List<DocxProfileReader.Source> sources = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Long id : ids) {
            Optional<ProjectFile> opt = projectFileRepository.findById(id);
            if (opt.isEmpty()) {
                return "Error: File not found in database: " + id;
            }
            ProjectFile pf = opt.get();
            String denied = ToolFileGuard.rejectIfOutsideProject(pf);
            if (denied != null) return denied;
            String lower = String.valueOf(pf.getName()).toLowerCase();
            String type = pf.getFileType() == null ? "" : pf.getFileType().toLowerCase();
            if ("doc".equals(type) || lower.endsWith(".doc") || "wps".equals(type) || lower.endsWith(".wps")) {
                skipped.add("「" + pf.getName() + "」" + DOC_HINT);
                continue;
            }
            if (!"docx".equals(type) && !lower.endsWith(".docx")) {
                skipped.add("「" + pf.getName() + "」不是 .docx，已跳过。");
                continue;
            }
            try {
                Resource res = storageServiceFactory.getStorageService().load(pf.getFilePath());
                byte[] bytes;
                try (InputStream in = res.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                sources.add(new DocxProfileReader.Source(pf.getId(), pf.getName(), new ByteArrayInputStream(bytes)));
            } catch (Exception e) {
                log.warn("docx_inspect_template: load failed fileId={}", id, e);
                return "Error: cannot read file " + id + ": " + e.getMessage();
            }
        }
        if (sources.isEmpty()) {
            return skipped.isEmpty() ? "Error: no readable .docx template." : String.join("\n", skipped);
        }
        try {
            StyleProfile profile = DocxProfileReader.read(sources);
            String name = optionName(options);
            if (name != null) profile.root().put("name", name);
            String json = StyleProfiles.toJson(profile);
            // 自己落盘：doc_apply_style_profile / write_docx 只认项目里的 _模板/画像.json。
            // 以前只把 JSON 交给模型让它 write_file 转抄——实测会掉字段（黄金对照 B v6/v7）。
            Long projectId = sources.isEmpty() ? null : projectIdOf(ids);
            try {
                ProjectFile saved = saveProjectProfile(projectId, json);
                if (saved != null) {
                    profile.root().put("savedProfileFileId", saved.getId());
                    profile.root().put("savedProfilePath", StyleProfileResolver.TEMPLATE_FOLDER + "/" + StyleProfileResolver.PROFILE_FILE);
                } else {
                    profile.root().put("saveError", "没有项目上下文，画像未保存：请用 write_file 写入 "
                            + StyleProfileResolver.TEMPLATE_FOLDER + "/" + StyleProfileResolver.PROFILE_FILE);
                }
            } catch (Exception e) {
                log.warn("docx_inspect_template: 画像落盘失败", e);
                profile.root().put("saveError", "画像未能保存（" + e.getMessage() + "）：请用 write_file 写入 "
                        + StyleProfileResolver.TEMPLATE_FOLDER + "/" + StyleProfileResolver.PROFILE_FILE);
            }
            StringBuilder sb = new StringBuilder();
            if (!skipped.isEmpty()) {
                sb.append(String.join("\n", skipped)).append('\n');
            }
            sb.append(StyleProfiles.toJson(profile));
            return ToolFileGuard.capToolText("styleProfile", sb.toString());
        } catch (Exception e) {
            log.warn("docx_inspect_template failed", e);
            return "Error: failed to learn template: " + e.getMessage();
        }
    }

    /** 模板文件所属项目（多份取第一份能查到的）。 */
    private Long projectIdOf(List<Long> ids) {
        for (Long id : ids) {
            Optional<ProjectFile> opt = projectFileRepository.findById(id);
            if (opt.isPresent() && opt.get().getProjectId() != null) return opt.get().getProjectId();
        }
        return null;
    }

    /**
     * 把画像写进项目的 {@code _模板/画像.json}：同名就地覆盖（不生成「画像 (1).json」），
     * 没有项目上下文或没有 ProjectFileService（单测）时返回 null。
     */
    ProjectFile saveProjectProfile(Long projectId, String json) {
        if (projectId == null || projectFileService == null) return null;
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ProjectFile folder = projectFileService.ensureFolderPath(projectId, AGENT_USER_ID,
                List.of(StyleProfileResolver.TEMPLATE_FOLDER));
        Optional<ProjectFile> existing = projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(
                projectId, folder.getId(), StyleProfileResolver.PROFILE_FILE);
        ProjectFile target = existing.orElseGet(() -> projectFileService.createFile(projectId, folder.getId(),
                StyleProfileResolver.PROFILE_FILE, "json", (long) data.length, null, null, AGENT_USER_ID));
        storageServiceFactory.getStorageService().save(target.getFilePath(), new ByteArrayInputStream(data));
        if (existing.isPresent()) {
            projectFileService.createOrUpdateFile(projectId, folder.getId(), StyleProfileResolver.PROFILE_FILE,
                    "json", (long) data.length, target.getFilePath(), null, AGENT_USER_ID);
        }
        return target;
    }

    static List<Long> parseIds(String fileIds) {
        List<Long> out = new ArrayList<>();
        if (fileIds == null) return out;
        for (String s : fileIds.split("[,，;\\s\\[\\]]+")) {
            if (s.isBlank()) continue;
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignore) {
                // 非数字片段跳过
            }
        }
        return out;
    }

    private static String optionName(String options) {
        if (options == null || options.isBlank()) return null;
        try {
            var n = StyleProfiles.mapper().readTree(options);
            return n != null && n.hasNonNull("name") ? n.get("name").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
