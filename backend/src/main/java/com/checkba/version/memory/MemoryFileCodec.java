package com.checkba.version.memory;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一条记忆一个文件 {scope}/{uid}.md 的编解码：YAML front-matter（结构字段）+ 正文（memoryValue）。
 *
 * 设计要点：
 * - 时间用 epoch 毫秒 + ISO 字符串双写，LWW 比较只认毫秒——LocalDateTime 是机器本地时间，
 *   跨时区机器直接比较会错序；落文件前统一按本机时区换成 Instant。
 * - 删除用墓碑（tombstone: true）不删文件：文件还在，陈旧端把旧内容推上来时合并层
 *   能看见「这条已经被删过」，不会复活（spec Phase A 第 5 条）。
 * - 编码确定性：LinkedHashMap 固定字段序 + BLOCK 风格 dump，同一输入永远得到同一字节，
 *   「内容没变就不产生新提交」靠这一点成立。
 * - 解析用 SafeConstructor：文件来自 git 远端，内容不可信，不允许 YAML 反序列化任意类型。
 */
public final class MemoryFileCodec {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Pattern UID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    private MemoryFileCodec() {}

    /**
     * 文件形态的一条记忆。语义字段（equalsIgnoringTime 比较的部分）变了才值得写文件/回灌，
     * 时间戳单独比较（LWW）——否则回灌时 JPA 的 @PreUpdate 会把 updatedAt 推到当前时刻，
     * 下一轮导出又产生「只有时间戳变了」的新文件，两台机器之间无限打乒乓。
     */
    public record MemoryFileData(String uid, String scope, String memoryType, String memoryKey,
                                 String memoryValue, Double importanceScore, Boolean isProtected,
                                 String conversationId, String author, String sourceFileUid,
                                 Map<String, Object> metadata,
                                 Long createdAtMs, Long updatedAtMs, boolean tombstone) {

        public boolean semanticallyEquals(MemoryFileData other) {
            if (other == null) return false;
            return tombstone == other.tombstone
                    && Objects.equals(scope, other.scope)
                    && Objects.equals(memoryType, other.memoryType)
                    && Objects.equals(memoryKey, other.memoryKey)
                    && Objects.equals(memoryValue, other.memoryValue)
                    && Objects.equals(importanceScore, other.importanceScore)
                    && Objects.equals(isProtected, other.isProtected)
                    && Objects.equals(conversationId, other.conversationId)
                    && Objects.equals(sourceFileUid, other.sourceFileUid)
                    && Objects.equals(metadata, other.metadata);
        }
    }

    /** uid 是否是合法的文件名成分（UUID 形态）；路径安全的一部分，不合法的一律不落盘。 */
    public static boolean isValidUid(String uid) {
        return uid != null && UID_PATTERN.matcher(uid).matches();
    }

    public static byte[] encode(MemoryFileData d) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("uid", d.uid());
        fm.put("scope", d.scope());
        fm.put("memoryType", d.memoryType());
        if (d.memoryKey() != null) fm.put("memoryKey", d.memoryKey());
        if (d.importanceScore() != null) fm.put("importanceScore", d.importanceScore());
        if (d.isProtected() != null) fm.put("isProtected", d.isProtected());
        if (d.conversationId() != null) fm.put("conversationId", d.conversationId());
        if (d.author() != null) fm.put("author", d.author());
        if (d.sourceFileUid() != null) fm.put("sourceFileUid", d.sourceFileUid());
        if (d.metadata() != null && !d.metadata().isEmpty()) fm.put("metadata", d.metadata());
        if (d.createdAtMs() != null) {
            fm.put("createdAtMs", d.createdAtMs());
            fm.put("createdAt", Instant.ofEpochMilli(d.createdAtMs()).toString());
        }
        if (d.updatedAtMs() != null) {
            fm.put("updatedAtMs", d.updatedAtMs());
            fm.put("updatedAt", Instant.ofEpochMilli(d.updatedAtMs()).toString());
        }
        if (d.tombstone()) fm.put("tombstone", true);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        String yaml = new Yaml(options).dump(fm);

        String body = d.memoryValue() == null ? "" : d.memoryValue();
        return ("---\n" + yaml + "---\n\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析文件；uidFromPath 来自文件名（身份的唯一权威——front-matter 里的 uid 只作冗余，
     * 两者不一致以路径为准）。格式非法返回 null，调用方跳过并告警，不炸整个同步。
     */
    @SuppressWarnings("unchecked")
    public static MemoryFileData decode(String uidFromPath, byte[] content) {
        try {
            String text = new String(content, StandardCharsets.UTF_8);
            if (!text.startsWith("---\n")) return null;
            int end = text.indexOf("\n---\n", 3);
            if (end < 0) return null;
            String front = text.substring(4, end + 1);
            String body = text.substring(end + 5);
            if (body.startsWith("\n")) body = body.substring(1);

            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(front);
            if (!(loaded instanceof Map)) return null;
            Map<String, Object> fm = (Map<String, Object>) loaded;

            Map<String, Object> metadata = fm.get("metadata") instanceof Map
                    ? (Map<String, Object>) fm.get("metadata") : null;
            return new MemoryFileData(
                    uidFromPath,
                    str(fm.get("scope")),
                    str(fm.get("memoryType")),
                    str(fm.get("memoryKey")),
                    body,
                    dbl(fm.get("importanceScore")),
                    bool(fm.get("isProtected")),
                    str(fm.get("conversationId")),
                    str(fm.get("author")),
                    str(fm.get("sourceFileUid")),
                    metadata,
                    lng(fm.get("createdAtMs")),
                    lng(fm.get("updatedAtMs")),
                    Boolean.TRUE.equals(bool(fm.get("tombstone"))));
        } catch (Exception e) {
            return null;
        }
    }

    public static Long toEpochMs(LocalDateTime t) {
        return t == null ? null : t.atZone(ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime fromEpochMs(Long ms) {
        return ms == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZONE);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Double dbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }

    private static Long lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }

    private static Boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return null;
    }
}
