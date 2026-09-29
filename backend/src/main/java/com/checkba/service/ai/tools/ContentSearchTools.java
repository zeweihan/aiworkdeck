// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ContentSearchService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 跨项目文件的全文检索（dev-board#1065，审计 T-03）——Claude Code {@code Grep} 的对位。
 *
 * <p>病灶：用户问「哪份材料里提到了违约金」时，模型手上没有任何一个工具能按<b>正文</b>找文件。
 * 唯一自称会搜内容的 {@code doc_search_related_docs} 其实只比文件名（内容搜索是个 TODO），
 * 无命中时还回「项目里前 10 个可编辑文档」冒充结果；模型只能逐份 {@code extract_file_text}
 * 把每份最多 8 万字符的全文灌进上下文。而产品里早有一套现成的全文检索
 * {@link ContentSearchService}（正则 / 整词 / 类型过滤，抽取结果走落库缓存、有界线程池并行），
 * 只给界面的搜索面板用。这里把它包成工具，搜索逻辑一行不重写。
 *
 * <p>三个「找」的分工写进描述里，模型才分得清：按文件名找是 {@code search_project_files}（Glob），
 * 按正文找是本工具（Grep），在已打开的那一份文档里找是 {@code doc_find_text} / {@code office_search}。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentSearchTools implements AgentToolComponent {

    /** 一次最多列多少处命中：再多就该缩小关键词，而不是把几百行片段灌进上下文。 */
    static final int MAX_HITS = 50;

    /** 单处命中片段的字符上限（服务端片段约一百字，这里再兜一道）。 */
    static final int MAX_SNIPPET_CHARS = 200;

    private final ContentSearchService contentSearchService;
    private final ProjectFileRepository projectFileRepository;

    @ToolMeta(displayName = "搜索文件内容", category = "file")
    @Tool("Full-text search across the TEXT of every project file (Word/Excel/PowerPoint, text-layer PDF, "
            + "txt/md/csv): answers 'which material mentions X, and where'. Returns up to " + MAX_HITS
            + " hits, each as 'name (fileId=N) [path] line L: matched snippet'; then read the surrounding text "
            + "with extract_file_text(fileId, offset). "
            + "Pick the right finder: to find a file by its NAME use search_project_files; to find text inside the "
            + "document that is OPEN in the editor use doc_find_text (or office_search in the Office add-in); "
            + "to find which PROJECT FILE contains a phrase use this tool. "
            + "Plain words are matched literally and case-insensitively; set useRegex=true for a Java regular "
            + "expression, wholeWord=true to match whole words only (useful for English terms). "
            + "Scanned PDFs and images are searchable only after they have been read once (their OCR text is cached).")
    public String search_project_content(
            @P("Text to look for, e.g. '违约金' or '第八条'; a regular expression when useRegex=true") String query,
            @P(value = "Optional: true to treat query as a Java regular expression (default false)",
                    required = false) Boolean useRegex,
            @P(value = "Optional: true to match whole words only (default false)", required = false) Boolean wholeWord,
            @P(value = "Optional: comma-separated extensions to search, e.g. 'docx,pdf' (default: all searchable types)",
                    required = false) String fileTypes
    ) {
        log.info("Tool: search_project_content called regex={} wholeWord={} types={}", useRegex, wholeWord, fileTypes);
        if (!StringUtils.hasText(query)) {
            return "Error: query is required (the text to look for).";
        }
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "Error: no project context for this request.";
        }
        boolean regex = Boolean.TRUE.equals(useRegex);
        if (regex) {
            // 服务层遇到坏正则会静默退回字面匹配——对界面是体贴，对模型是误导：它会以为
            // 自己的正则生效了、结果就是全部。这里先编译一次，坏了就明说。
            try {
                Pattern.compile(query.trim());
            } catch (PatternSyntaxException e) {
                return "Error: query is not a valid regular expression (" + e.getDescription()
                        + "). Fix it, or call again with useRegex=false for a literal search.";
            }
        }

        SearchRequest request = new SearchRequest();
        request.setQuery(query.trim());
        request.setUseRegex(regex);
        request.setWholeWord(Boolean.TRUE.equals(wholeWord));
        List<String> types = parseTypes(fileTypes);
        if (!types.isEmpty()) {
            request.setFileTypes(types);
        }

        SearchResult result;
        try {
            result = contentSearchService.searchContent(projectId, request);
        } catch (Exception e) {
            log.warn("search_project_content failed", e);
            return "Error: content search failed: " + e.getMessage();
        }
        List<SearchResult.FileSearchResult> files =
                result == null || result.getResults() == null ? List.of() : result.getResults();
        if (files.isEmpty()) {
            return "No matches: no project file mentions '" + query.trim() + "'"
                    + (types.isEmpty() ? "" : " (searched types: " + String.join(",", types) + ")")
                    + ". Scanned PDFs and images that were never read are not covered; "
                    + "to find a file by its name use search_project_files.";
        }
        return render(query.trim(), files, result.getTotalMatches(), pathIndex(projectId));
    }

    private static String render(String query, List<SearchResult.FileSearchResult> files, int totalMatches,
                                 Map<Long, String> paths) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(query).append("」命中 ").append(files.size()).append(" 个文件");
        if (totalMatches > 0) {
            sb.append("、正文共 ").append(totalMatches).append(" 处");
        }
        sb.append("：\n");
        int shown = 0;
        int hiddenHits = 0;
        for (SearchResult.FileSearchResult f : files) {
            String head = "- " + f.getFileName() + " (fileId=" + f.getFileId() + ")"
                    + (paths.containsKey(f.getFileId()) ? " [" + paths.get(f.getFileId()) + "]" : "");
            List<SearchResult.MatchInfo> matches = f.getMatches() == null ? List.of() : f.getMatches();
            if (matches.isEmpty()) {
                if (shown >= MAX_HITS) {
                    hiddenHits++;
                    continue;
                }
                sb.append(head).append("：文件名命中（正文里没有）\n");
                shown++;
                continue;
            }
            for (SearchResult.MatchInfo m : matches) {
                if (shown >= MAX_HITS) {
                    hiddenHits++;
                    continue;
                }
                sb.append(head).append(" 第 ").append(m.getLineNumber()).append(" 行：")
                        .append(snippet(m.getContent())).append('\n');
                shown++;
            }
            // 服务层每个文件最多带 50 条片段，matchCount 可能更多——多出来的也要让模型知道
            if (f.getMatchCount() > matches.size()) {
                hiddenHits += f.getMatchCount() - matches.size();
            }
        }
        if (hiddenHits > 0) {
            sb.append("（还有 ").append(hiddenHits).append(" 处未列出：换个更具体的关键词，或用 fileTypes 缩小范围）\n");
        }
        sb.append("看某处上下文：extract_file_text(fileId, offset) 读那份文件。");
        return sb.toString();
    }

    private static String snippet(String content) {
        String s = content == null ? "" : content.replace('\n', ' ').trim();
        return s.length() <= MAX_SNIPPET_CHARS ? s : s.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    /** 「docx, .PDF」→ [docx, pdf]；空串 = 不过滤。 */
    static List<String> parseTypes(String raw) {
        List<String> types = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return types;
        }
        for (String piece : raw.split("[,，;\\s]+")) {
            String t = piece.trim().toLowerCase(Locale.ROOT);
            while (t.startsWith(".") || t.startsWith("*")) {
                t = t.substring(1);
            }
            if (!t.isEmpty() && !types.contains(t)) {
                types.add(t);
            }
        }
        return types;
    }

    /** fileId → 项目内相对路径（与 list_files / search_project_files 同一套路径口径）。 */
    private Map<Long, String> pathIndex(Long projectId) {
        Map<Long, String> byId = new HashMap<>();
        if (projectFileRepository == null) {
            return byId;
        }
        try {
            for (Map.Entry<String, ProjectFile> e : FileTools.dbPathIndex(projectFileRepository, projectId).entrySet()) {
                byId.put(e.getValue().getId(), e.getKey());
            }
        } catch (Exception e) {
            // 路径只是方便模型认文件，拿不到就不显示，不影响命中本身
            log.debug("search_project_content: 取项目路径索引失败 {}", e.toString());
        }
        return byId;
    }
}
