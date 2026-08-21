package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
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

    private final ProjectFileRepository projectFileRepository;
    private final StorageServiceFactory storageServiceFactory;

    @ToolMeta(displayName = "学习模板格式", category = "file")
    @Tool("Learn the formatting profile (styleProfile v1 JSON) of one or more team template .docx files: body/heading "
            + "fonts (eastAsia + western), sizes, alignment, spacing, line spacing, first-line indent, heading numbering "
            + "(auto numPr vs literal text like 一、（一）1.), table borders (table vs cell level), column widths, header row, "
            + "page setup, header/footer page-number pattern, TOC field. Pass the database fileIds of the templates "
            + "(comma-separated for several; the majority value wins and a confidence is reported). The returned JSON can be "
            + "saved as the project's 画像.json and passed to write_docx as styleProfileJson.")
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
            StringBuilder sb = new StringBuilder();
            if (!skipped.isEmpty()) {
                sb.append(String.join("\n", skipped)).append('\n');
            }
            sb.append(json);
            return ToolFileGuard.capToolText("styleProfile", sb.toString());
        } catch (Exception e) {
            log.warn("docx_inspect_template failed", e);
            return "Error: failed to learn template: " + e.getMessage();
        }
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
