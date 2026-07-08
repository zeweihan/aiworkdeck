package com.checkba.service.ai.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法律信息保护器
 * 识别并保护法律关键信息，确保在上下文压缩时不被丢失
 *
 * 保护正则外置于资源文件 legal/protected-patterns.yml，此类只负责加载与匹配。
 */
@Service
public class LegalInfoProtector {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LegalInfoProtector.class);

    /** 保护正则配置资源路径（classpath） */
    static final String PATTERNS_RESOURCE = "legal/protected-patterns.yml";

    // 需要保护的法律信息类型及其正则模式（从资源文件加载）
    private final List<ProtectedPattern> protectedPatterns;

    // 独立提取器正则（从资源文件加载）
    private final Pattern legalReferencePattern;
    private final Pattern amountPattern;
    private final Pattern datePattern;

    public LegalInfoProtector() {
        Map<String, Object> config = loadPatternConfig();

        List<ProtectedPattern> patterns = new ArrayList<>();
        Object rawPatterns = config.get("protected-patterns");
        if (rawPatterns instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    String pattern = String.valueOf(entry.get("pattern"));
                    ProtectionLevel level = ProtectionLevel.valueOf(String.valueOf(entry.get("level")));
                    String type = String.valueOf(entry.get("type"));
                    patterns.add(new ProtectedPattern(pattern, level, type));
                }
            }
        }
        if (patterns.isEmpty()) {
            throw new IllegalStateException("No protected patterns loaded from " + PATTERNS_RESOURCE);
        }
        this.protectedPatterns = Collections.unmodifiableList(patterns);

        Object rawExtractors = config.get("extractors");
        if (!(rawExtractors instanceof Map<?, ?> extractors)) {
            throw new IllegalStateException("No extractors section in " + PATTERNS_RESOURCE);
        }
        this.legalReferencePattern = Pattern.compile(String.valueOf(extractors.get("legal-reference")));
        this.amountPattern = Pattern.compile(String.valueOf(extractors.get("amount")));
        this.datePattern = Pattern.compile(String.valueOf(extractors.get("date")));

        log.info("LegalInfoProtector loaded {} protected patterns from {}", protectedPatterns.size(), PATTERNS_RESOURCE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadPatternConfig() {
        try (java.io.InputStream in = LegalInfoProtector.class.getClassLoader()
                .getResourceAsStream(PATTERNS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Pattern resource not found on classpath: " + PATTERNS_RESOURCE);
            }
            Object loaded = new org.yaml.snakeyaml.Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new IllegalStateException("Invalid pattern resource format: " + PATTERNS_RESOURCE);
            }
            return (Map<String, Object>) loaded;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load pattern resource: " + PATTERNS_RESOURCE, e);
        }
    }

    /**
     * 保护级别
     */
    public enum ProtectionLevel {
        CRITICAL(1.0),   // 绝对不能丢失
        HIGH(0.9),       // 非常重要
        MEDIUM(0.7);     // 较重要

        private final double score;

        ProtectionLevel(double score) {
            this.score = score;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * 保护模式
     */
    /**
     * 保护模式
     */
    public static class ProtectedPattern {
        private String pattern;
        private ProtectionLevel level;
        private String type;

        public ProtectedPattern(String pattern, ProtectionLevel level, String type) {
            this.pattern = pattern;
            this.level = level;
            this.type = type;
        }
        
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public ProtectionLevel getLevel() { return level; }
        public void setLevel(ProtectionLevel level) { this.level = level; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    /**
     * 受保护的内容片段
     */
    public static class ProtectedSegment {
        private int start;
        private int end;
        private String content;
        private ProtectionLevel level;
        private String type;

        public ProtectedSegment(int start, int end, String content, ProtectionLevel level, String type) {
            this.start = start;
            this.end = end;
            this.content = content;
            this.level = level;
            this.type = type;
        }
        
        public int getStart() { return start; }
        public void setStart(int start) { this.start = start; }
        public int getEnd() { return end; }
        public void setEnd(int end) { this.end = end; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public ProtectionLevel getLevel() { return level; }
        public void setLevel(ProtectionLevel level) { this.level = level; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    /**
     * 标记内容中的受保护信息
     */
    public List<ProtectedSegment> markProtectedInfo(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProtectedSegment> segments = new ArrayList<>();

        for (ProtectedPattern pp : protectedPatterns) {
            try {
                Pattern pattern = Pattern.compile(pp.getPattern());
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    segments.add(new ProtectedSegment(
                            matcher.start(),
                            matcher.end(),
                            matcher.group(),
                            pp.getLevel(),
                            pp.getType()
                    ));
                }
            } catch (Exception e) {
                log.warn("Failed to match pattern {}: {}", pp.getPattern(), e.getMessage());
            }
        }

        // 按位置排序并去重
        segments.sort(Comparator.comparingInt(ProtectedSegment::getStart));
        return mergeOverlappingSegments(segments);
    }

    /**
     * 合并重叠的片段
     */
    private List<ProtectedSegment> mergeOverlappingSegments(List<ProtectedSegment> segments) {
        if (segments.size() <= 1) {
            return segments;
        }

        List<ProtectedSegment> merged = new ArrayList<>();
        ProtectedSegment current = segments.get(0);

        for (int i = 1; i < segments.size(); i++) {
            ProtectedSegment next = segments.get(i);
            if (next.getStart() <= current.getEnd()) {
                // 重叠，合并
                int newEnd = Math.max(current.getEnd(), next.getEnd());
                ProtectionLevel higherLevel = current.getLevel().getScore() >= next.getLevel().getScore() 
                        ? current.getLevel() : next.getLevel();
                current = new ProtectedSegment(
                        current.getStart(),
                        newEnd,
                        current.getContent(),  // 保留第一个的内容
                        higherLevel,
                        current.getType()
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * 安全压缩：确保受保护信息不被丢失
     * 返回压缩后的内容和保护的信息列表
     */
    public CompressedResult safeCompress(String content, int targetLength) {
        List<ProtectedSegment> protectedSegments = markProtectedInfo(content);

        // 无受保护片段时：直接按长度处理。否则下方分段循环不会产生任何内容 → 返回空串丢失整段。
        if (protectedSegments.isEmpty()) {
            if (content.length() <= targetLength) {
                return new CompressedResult(content, protectedSegments, false);
            }
            return new CompressedResult(content.substring(0, Math.max(0, targetLength)) + "...", protectedSegments, true);
        }

        // 计算受保护内容的总长度
        int protectedLength = protectedSegments.stream()
                .mapToInt(s -> s.getContent().length())
                .sum();

        log.info("Safe compress: contentLength={}, targetLength={}, protectedLength={}, protectedCount={}",
                content.length(), targetLength, protectedLength, protectedSegments.size());

        if (protectedLength >= targetLength) {
            // 受保护内容已超过目标长度，只保留最关键的受保护内容
            StringBuilder sb = new StringBuilder();
            sb.append("[上下文已压缩，保留法律关键信息]\n\n");
            
            // 只保留 CRITICAL 级别的
            protectedSegments.stream()
                    .filter(s -> s.getLevel() == ProtectionLevel.CRITICAL)
                    .forEach(s -> sb.append("• ").append(s.getType()).append(": ")
                            .append(s.getContent()).append("\n"));
            
            return new CompressedResult(sb.toString(), protectedSegments, true);
        }

        // 还有空间保留其他内容
        int availableForOther = targetLength - protectedLength - 100; // 留100字符余量
        
        if (content.length() <= targetLength) {
            // 不需要压缩
            return new CompressedResult(content, protectedSegments, false);
        }

        // 需要压缩：保留受保护内容 + 非保护内容的摘要
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        
        for (ProtectedSegment segment : protectedSegments) {
            // 添加段前的部分内容（如果有空间）
            if (segment.getStart() > lastEnd && availableForOther > 0) {
                String before = content.substring(lastEnd, segment.getStart());
                int takeLength = Math.min(before.length(), availableForOther / protectedSegments.size());
                if (takeLength > 20) {
                    result.append(before.substring(0, takeLength)).append("...");
                    availableForOther -= takeLength;
                }
            }
            
            // 添加受保护内容
            result.append(segment.getContent());
            lastEnd = segment.getEnd();
        }
        
        return new CompressedResult(result.toString(), protectedSegments, true);
    }

    /**
     * 压缩结果
     */
    @Data
    @AllArgsConstructor
    public static class CompressedResult {
        private String content;
        private List<ProtectedSegment> protectedSegments;
        private boolean wasCompressed;
    }

    /**
     * 验证压缩后的内容是否安全
     */
    public ValidationResult validate(String original, String compressed) {
        List<ProtectedSegment> originalSegments = markProtectedInfo(original);
        List<ProtectedSegment> compressedSegments = markProtectedInfo(compressed);

        List<String> missing = new ArrayList<>();
        
        // 检查 CRITICAL 级别的内容是否都保留了
        for (ProtectedSegment os : originalSegments) {
            if (os.getLevel() == ProtectionLevel.CRITICAL) {
                boolean found = compressedSegments.stream()
                        .anyMatch(cs -> cs.getContent().contains(os.getContent()) || 
                                os.getContent().contains(cs.getContent()));
                if (!found) {
                    missing.add(os.getType() + ": " + os.getContent());
                }
            }
        }

        if (!missing.isEmpty()) {
            log.warn("Compression validation failed: missing {} critical items", missing.size());
            return new ValidationResult(false, missing);
        }

        log.info("Compression validation passed: {} critical items preserved", 
                originalSegments.stream().filter(s -> s.getLevel() == ProtectionLevel.CRITICAL).count());
        return new ValidationResult(true, Collections.emptyList());
    }

    /**
     * 验证结果
     */
    @Data
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private List<String> missingItems;
    }

    /**
     * 提取所有法律引用
     */
    public List<String> extractLegalReferences(String content) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = legalReferencePattern.matcher(content);
        while (matcher.find()) {
            String ref = matcher.group();
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * 提取所有金额
     */
    public List<String> extractAmounts(String content) {
        List<String> amounts = new ArrayList<>();
        Matcher matcher = amountPattern.matcher(content);
        while (matcher.find()) {
            amounts.add(matcher.group());
        }
        return amounts;
    }

    /**
     * 提取所有日期
     */
    public List<String> extractDates(String content) {
        List<String> dates = new ArrayList<>();
        Matcher matcher = datePattern.matcher(content);
        while (matcher.find()) {
            dates.add(matcher.group());
        }
        return dates;
    }
}

