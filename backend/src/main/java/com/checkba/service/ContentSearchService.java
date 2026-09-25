// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.model.dto.SearchResult.FileSearchResult;
import com.checkba.model.dto.SearchResult.MatchInfo;
import com.checkba.model.entity.FileTag;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.model.entity.ProjectFileTextCache;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.file.PdfTextLayer;
import com.checkba.service.file.ProjectFileTextCacheService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内容搜索服务
 *
 * 复用 {@link DocumentTextService} 提取文档内容进行全文搜索
 * 支持: DOCX, PDF, PPTX, XLSX, TXT, MD
 *
 * <p>性能（v0.49.0 BUG-12）：此前每次请求都把每个文件从头抽一遍、逐个串行，
 * 100 个文件一次 8-15 秒；DocumentTextService 那层 32 条的 LRU 对「按同一顺序扫
 * 100 个文件」命中率是 0。现在抽取结果走 {@link ProjectFileTextCacheService}
 *（键 fileId，失效判据是物理文件 mtime+size，与 AI 读文件共用同一份判据与同一张表），
 * 未命中的文件在一个有界线程池里并行抽取与匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSearchService {

    private final ProjectFileRepository projectFileRepository;
    private final FileTagRepository fileTagRepository;
    private final com.checkba.repository.TagRepository tagRepository;
    private final DocumentTextService documentTextService;
    /** 抽取结果的跨重启缓存；单测传 null 即退化成「每次重抽」。 */
    private final ProjectFileTextCacheService textCache;

    /**
     * 抽取与匹配的有界线程池，所有搜索请求共用。
     *
     * <p>共用而不是每个请求各开一组：用户连续输入叠出来的多个请求会在这里排队，
     * 而不是各自再乘上 N 个线程去抢 CPU；排在后面的请求轮到时，前一个请求已经把
     * 抽取结果写进缓存，自然就命中了。任务里不再向池子提交任务，所以不会自锁。
     */
    private final ExecutorService searchPool = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "content-search-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Set<String> SEARCHABLE_TYPES = Set.of(
        "docx", "doc", "pdf", "pptx", "ppt", "xlsx", "xls", "txt", "md", "csv"
    );

    private static final int MAX_CONTEXT_CHARS = 100;
    private static final int MAX_MATCHES_PER_FILE = 50;

    @PreDestroy
    public void shutdown() {
        searchPool.shutdownNow();
    }

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

        // 逐文件并行：抽取（先查缓存）+ 匹配。按文件原顺序收集，结果顺序不随线程调度变化。
        List<Future<FileSearchResult>> futures = new ArrayList<>(files.size());
        for (ProjectFile file : files) {
            futures.add(searchPool.submit(() -> searchOneFile(file, hasQuery, query, queryLower,
                    searchPattern, request, fileTagsMap)));
        }
        for (int i = 0; i < futures.size(); i++) {
            FileSearchResult fileResult;
            try {
                fileResult = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                break;
            } catch (ExecutionException e) {
                // searchOneFile 自己已经兜住 Throwable，走到这里只会是池子被关等极端情况
                log.warn("Failed to search file {}: {}", files.get(i).getName(), e.getMessage());
                continue;
            }
            if (fileResult != null) {
                results.add(fileResult);
                totalMatches += fileResult.getMatchCount();
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
     * 单个文件的搜索：文件名匹配 + 内容匹配。不命中返回 null。在 {@link #searchPool} 里跑。
     */
    private FileSearchResult searchOneFile(ProjectFile file, boolean hasQuery, String query, String queryLower,
                                           Pattern searchPattern, SearchRequest request,
                                           Map<Long, List<Tag>> fileTagsMap) {
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
                    matches = findMatches(content, searchPattern, query);
                    log.debug("[Search] File {} - {} chars, {} matches", file.getName(), content.length(), matches.size());
                } else {
                    log.debug("[Search] File {} has no extractable content", file.getName());
                }
            }

            // 3. 如果文件名匹配或内容匹配，或者没有查询词（纯标签过滤），都加入结果
            if (!hasQuery || fileNameMatches || !matches.isEmpty()) {
                return FileSearchResult.builder()
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
            }
        } catch (Throwable e) {
            // Throwable 而非 Exception：某些格式解析库在 classpath 不兼容时抛的是
            // Error（如 NoSuchMethodError），一旦漏挡就会打断整个搜索请求、
            // 连带丢掉本该找到的其它文件命中。
            log.warn("Failed to search file {}: {}", file.getName(), e.getMessage(), e);
        }
        return null;
    }

    /**
     * 提取文件内容。委托 {@link DocumentTextService}：PDF 走 PDFBox3 原生 API，
     * 不再自建 Tika 直接解析 PDF——Tika 2.9.1 的 PDFParser 调 PDFBox2 已删除的
     * PDDocument.load 会抛 NoSuchMethodError（Error，非 Exception），
     * 曾经穿透这里的 catch 把整个搜索请求打挂。
     */
    private String extractContent(ProjectFile file) {
        String filePath = file.getFilePath();
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        try {
            // 先查落库缓存（dev-board#800 那张表，AI 读文件也写它）：文件没改就不再跑 Tika/PDFBox。
            // 命中的可能是早先 OCR 得到的正文——扫描件因此也能被搜到，且不花一分钱。
            DocumentTextService.FileStamp stamp =
                    textCache == null || file.getId() == null ? null : documentTextService.stampOf(file);
            String cached = textCache == null ? null : textCache.find(file.getId(), stamp);
            if (cached != null) {
                return cached;
            }
            String extracted = documentTextService.extractText(file);
            log.debug("[Search] extracted {} chars from {}",
                extracted != null ? extracted.length() : 0, filePath);
            // 写缓存的判据与 ProjectFileTextExtractor 一致：PDF 文字层不可用（扫描件残渣）
            // 不写——那张表是共用的，写进去 AI 读这份文件时会拿残渣顶替 OCR。
            if (textCache != null && isUsableText(file, extracted)) {
                textCache.store(file.getId(), stamp, ProjectFileTextCache.SOURCE_TEXT, extracted);
            }
            return extracted;
        } catch (Exception e) {
            log.warn("Failed to extract content from {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private static boolean isUsableText(ProjectFile file, String text) {
        boolean pdf = "pdf".equalsIgnoreCase(file.getFileType())
                || (file.getName() != null && file.getName().toLowerCase().endsWith(".pdf"));
        return pdf ? PdfTextLayer.isUsable(text) : text != null && !text.isBlank();
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
