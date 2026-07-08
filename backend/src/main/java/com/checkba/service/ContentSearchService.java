package com.checkba.service;

import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.model.dto.SearchResult.FileSearchResult;
import com.checkba.model.dto.SearchResult.MatchInfo;
import com.checkba.model.entity.FileTag;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.ProjectFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内容搜索服务
 * 
 * 使用 Apache Tika 提取文档内容进行全文搜索
 * 支持: DOCX, PDF, PPTX, XLSX, TXT, MD
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSearchService {

    private final ProjectFileRepository projectFileRepository;
    private final FileTagRepository fileTagRepository;
    private final com.checkba.repository.TagRepository tagRepository;
    private final com.checkba.storage.StorageServiceFactory storageServiceFactory;
    private final Tika tika = new Tika();

    private static final Set<String> SEARCHABLE_TYPES = Set.of(
        "docx", "doc", "pdf", "pptx", "ppt", "xlsx", "xls", "txt", "md", "csv"
    );

    private static final int MAX_CONTEXT_CHARS = 100;
    private static final int MAX_MATCHES_PER_FILE = 50;

    /**
     * 在项目中搜索内容
     */
    public SearchResult searchContent(Long projectId, SearchRequest request) {
        String query = request.getQuery() != null ? request.getQuery().trim() : "";
        boolean hasQuery = !query.isEmpty();
        boolean isTagOnlySearch = !hasQuery && request.getTagIds() != null && !request.getTagIds().isEmpty();
        
        log.info("[Search] projectId={}, query='{}', hasQuery={}, isTagOnlySearch={}, tagIds={}", 
            projectId, query, hasQuery, isTagOnlySearch, request.getTagIds());

        if (!hasQuery && !isTagOnlySearch) {
            return SearchResult.builder()
                .totalMatches(0)
                .totalFiles(0)
                .results(Collections.emptyList())
                .build();
        }

        String queryLower = query.toLowerCase();

        // 0. Resolve allowed file IDs if tags are specified
        Set<Long> allowedFileIds = null;
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            List<FileTag> fileTags = fileTagRepository.findByTagIdIn(request.getTagIds());
            
            // AND logic: File must have ALL selected tags
            // Group by fileId and count matches
            Map<Long, Long> fileTagCounts = fileTags.stream()
                .collect(Collectors.groupingBy(FileTag::getFileId, Collectors.counting()));
            
            int requiredCount = request.getTagIds().size();
            allowedFileIds = fileTagCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == requiredCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            
            log.info("[Search] Tag filter (AND logic): Found matches for tagIds {}. allowedFileIds size: {}", 
                request.getTagIds(), allowedFileIds.size());

            // If tags selected but no files match, return empty early
            if (allowedFileIds.isEmpty()) {
                 log.info("[Search] allowedFileIds is empty, returning empty result");
                 return SearchResult.builder()
                    .totalMatches(0)
                    .totalFiles(0)
                    .results(Collections.emptyList())
                    .build();
            }
        }
        final Set<Long> finalAllowedFileIds = allowedFileIds;
        
        // 获取项目所有可搜索文件
        // 如果是纯标签搜索，不过滤文件类型（允许搜索所有已被打标签的文件，包括图片等）
        List<ProjectFile> files = projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId)
            .stream()
            .filter(f -> !"folder".equals(f.getFileType()))
            .filter(f -> isTagOnlySearch || isSearchableType(f.getFileType()))
            .filter(f -> isTagOnlySearch || matchesTypeFilter(f, request.getFileTypes()))
            .filter(f -> finalAllowedFileIds == null || finalAllowedFileIds.contains(f.getId()))
            .collect(Collectors.toList());
        
        log.info("Searching {} files in project {} for query: {} (tagOnly={})", 
            files.size(), projectId, query, isTagOnlySearch);

        // Pre-fetch all tags for the result files to avoid N+1 queries
        Map<Long, List<Tag>> fileTagsMap = new HashMap<>();
        if (!files.isEmpty()) {
            List<Long> resultFileIds = files.stream().map(ProjectFile::getId).collect(Collectors.toList());
            List<FileTag> allFileTags = fileTagRepository.findByFileIdIn(resultFileIds);
            
            if (!allFileTags.isEmpty()) {
                Set<Long> allTagIds = allFileTags.stream().map(FileTag::getTagId).collect(Collectors.toSet());
                List<Tag> allTags = tagRepository.findAllById(allTagIds);
                Map<Long, Tag> tagMap = allTags.stream().collect(Collectors.toMap(Tag::getId, t -> t));
                
                for (FileTag ft : allFileTags) {
                    if (tagMap.containsKey(ft.getTagId())) {
                        fileTagsMap.computeIfAbsent(ft.getFileId(), k -> new ArrayList<>()).add(tagMap.get(ft.getTagId()));
                    }
                }
            }
        }

        List<FileSearchResult> results = new ArrayList<>();
        int totalMatches = 0;

        // 构建搜索模式 (仅当有查询词时)
        Pattern searchPattern = hasQuery ? buildSearchPattern(query, request) : null;

        for (ProjectFile file : files) {
            try {
                List<MatchInfo> matches = new ArrayList<>();
                boolean fileNameMatches = false;
                
                // 1. 检查文件名是否匹配
                String fileName = file.getName();
                if (hasQuery && fileName != null) {
                    if (request.isCaseSensitive()) {
                        fileNameMatches = fileName.contains(query);
                    } else {
                        fileNameMatches = fileName.toLowerCase().contains(queryLower);
                    }
                }
                
                // 2. 提取内容并搜索 (仅当有查询词时)
                if (hasQuery) {
                    String content = extractContent(file);
                    if (content != null && !content.isEmpty()) {
                        log.info("[Search] File {} - extracted {} chars, searching for: {}", 
                            file.getName(), content.length(), query);
                        matches = findMatches(content, searchPattern, query);
                        log.info("[Search] File {} - found {} matches", file.getName(), matches.size());
                    } else {
                        log.debug("[Search] File {} has no extractable content", file.getName());
                    }
                }
                
                // 3. 如果文件名匹配或内容匹配，或者没有查询词（纯标签过滤），都加入结果
                if (!hasQuery || fileNameMatches || !matches.isEmpty()) {
                    FileSearchResult fileResult = FileSearchResult.builder()
                        .fileId(file.getId())
                        .wpsFileId(file.getWpsFileId())
                        .fileName(file.getName())
                        .filePath(file.getFilePath())
                        .fileType(file.getFileType())
                        .matchCount(matches.size())
                        .matches(matches.size() > MAX_MATCHES_PER_FILE 
                            ? matches.subList(0, MAX_MATCHES_PER_FILE) 
                            : matches)
                        .tags(fileTagsMap.getOrDefault(file.getId(), Collections.emptyList()))
                        .build();
                    results.add(fileResult);
                    totalMatches += matches.size();
                    
                    if (fileNameMatches && matches.isEmpty()) {
                        log.info("[Search] File {} matched by filename only", file.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to search file {}: {}", file.getName(), e.getMessage(), e);
            }
        }

        // 按匹配数排序（文件名匹配排在内容匹配之后）
        results.sort((a, b) -> Integer.compare(b.getMatchCount(), a.getMatchCount()));

        return SearchResult.builder()
            .totalMatches(totalMatches)
            .totalFiles(results.size())
            .results(results)
            .build();
    }

    /**
     * 提取文件内容
     */
    private String extractContent(ProjectFile file) {
        String filePath = file.getFilePath();
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        try {
            org.springframework.core.io.Resource resource = storageServiceFactory.getStorageService().load(filePath);
            if (!resource.exists()) {
                log.warn("File not found via StorageService: {}", filePath);
                return null;
            }
            
            try (java.io.InputStream is = resource.getInputStream()) {
                String extracted = tika.parseToString(is);
                log.info("[Search] Tika extracted {} chars from {}", 
                    extracted != null ? extracted.length() : 0, filePath);
                return extracted;
            }
        } catch (Exception e) {
            log.warn("Failed to extract content from {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 构建搜索正则表达式
     */
    private Pattern buildSearchPattern(String query, SearchRequest request) {
        String patternStr;
        
        if (request.isUseRegex()) {
            patternStr = query;
        } else {
            // 转义特殊字符
            patternStr = Pattern.quote(query);
            
            if (request.isWholeWord()) {
                patternStr = "\\b" + patternStr + "\\b";
            }
        }
        
        int flags = request.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
        flags |= Pattern.MULTILINE;
        
        try {
            return Pattern.compile(patternStr, flags);
        } catch (Exception e) {
            log.warn("Invalid regex pattern: {}", query);
            // 回退到普通搜索
            return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        }
    }

    /**
     * 在内容中查找匹配
     */
    private List<MatchInfo> findMatches(String content, Pattern pattern, String query) {
        List<MatchInfo> matches = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            try {
                // 用超时感知的 CharSequence 包装，防止用户提供的正则触发灾难性回溯挂死搜索线程
                Matcher matcher = pattern.matcher(
                        new TimeLimitedCharSequence(line, System.currentTimeMillis() + REGEX_TIMEOUT_MS));

                while (matcher.find()) {
                    int start = matcher.start();
                    int end = matcher.end();

                    // 提取上下文并同步得到匹配在其中的相对位置：
                    // 此前调用方自行按 (start-50 + "...") 推算偏移，与 extractContext 实际的 trim/短行整行/省略号
                    // 处理不一致，短行或行首有缩进时高亮错位。改由 extractContext 一并返回对齐后的偏移。
                    MatchContext ctx = extractContext(line, start, end);

                    matches.add(MatchInfo.builder()
                        .lineNumber(lineNumber)
                        .content(ctx.content())
                        .startIndex(ctx.start())
                        .endIndex(ctx.end())
                        .build());

                    if (matches.size() >= MAX_MATCHES_PER_FILE * 2) {
                        return matches;
                    }
                }
            } catch (RegexTimeoutException e) {
                log.warn("正则匹配超时（可能 ReDoS），放弃该文件剩余匹配（行 {}）", lineNumber);
                return matches;
            }
        }

        return matches;
    }

    private static final long REGEX_TIMEOUT_MS = 2000;

    /** 正则匹配超时（用于中断灾难性回溯）。无堆栈以降低开销。 */
    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() { super(null, null, false, false); }
    }

    /**
     * 包装 CharSequence，在正则回溯大量访问字符时检测超时，
     * 阻止 catastrophic backtracking（如 (a+)+）挂死搜索线程。
     */
    private static final class TimeLimitedCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadline;

        TimeLimitedCharSequence(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (System.currentTimeMillis() > deadline) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() { return inner.length(); }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TimeLimitedCharSequence(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() { return inner.toString(); }
    }

    /** 上下文片段 + 匹配在该片段中的相对起止（已对齐 trim/省略号）。 */
    private record MatchContext(String content, int start, int end) {}

    /**
     * 提取匹配上下文，并返回匹配在上下文中的相对起止位置（供前端高亮，与 content 严格对齐）。
     */
    private MatchContext extractContext(String line, int matchStart, int matchEnd) {
        if (line.length() <= MAX_CONTEXT_CHARS) {
            // 整行返回，但 trim 会移除前导空白，匹配位置需相应左移
            int leading = line.length() - line.stripLeading().length();
            String content = line.trim();
            int s = clamp(matchStart - leading, 0, content.length());
            int e = clamp(matchEnd - leading, s, content.length());
            return new MatchContext(content, s, e);
        }

        int contextStart = Math.max(0, matchStart - MAX_CONTEXT_CHARS / 2);
        int contextEnd = Math.min(line.length(), matchEnd + MAX_CONTEXT_CHARS / 2);
        String raw = line.substring(contextStart, contextEnd);

        int relStart = matchStart - contextStart;
        int relEnd = matchEnd - contextStart;
        // 处理左侧 trim（stripLeading）造成的偏移
        int leadingTrim = raw.length() - raw.stripLeading().length();
        String content = raw.trim();
        relStart = clamp(relStart - leadingTrim, 0, content.length());
        relEnd = clamp(relEnd - leadingTrim, relStart, content.length());

        int prefixLen = 0;
        if (contextStart > 0) {
            content = "..." + content;
            prefixLen = 3;
        }
        if (contextEnd < line.length()) {
            content = content + "...";
        }
        return new MatchContext(content, relStart + prefixLen, relEnd + prefixLen);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 检查文件类型是否可搜索
     */
    private boolean isSearchableType(String fileType) {
        if (fileType == null) return false;
        return SEARCHABLE_TYPES.contains(fileType.toLowerCase());
    }

    /**
     * 检查是否匹配文件类型过滤器
     */
    private boolean matchesTypeFilter(ProjectFile file, List<String> fileTypes) {
        if (fileTypes == null || fileTypes.isEmpty()) {
            return true;
        }
        String type = file.getFileType();
        if (type == null) return false;
        return fileTypes.stream().anyMatch(t -> t.equalsIgnoreCase(type));
    }
}
