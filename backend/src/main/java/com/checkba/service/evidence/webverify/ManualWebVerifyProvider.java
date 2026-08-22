package com.checkba.service.evidence.webverify;

import com.checkba.service.LangText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线网核适配器（dev-board#100 P3）：<b>不联网</b>，只解外部工具导出的 zip。
 *
 * <p>维护者 2026-08-21 拍板「网核只留接口，不做自动爬取，不碰验证码与合规风险」（spec §6/§7 第 1 条），
 * 所以这里没有、也不许有任何 HTTP 客户端、登录、验证码、反爬代码。
 *
 * <p>包内约定（两条解析路径，manifest 优先）：
 * <ol>
 * <li><b>manifest.json</b>（放包里任意位置，可缺）：
 * <pre>{@code
 * { "provider": "xxx", "party": "某某公司", "unifiedSocialCreditCode": "91…",
 *   "items": [ { "file": "gsxt.png", "site": "credit_publicity",
 *                "capturedAt": "2026-08-21T10:00:00+08:00", "sourceUrl": "https://…",
 *                "text": "页面文本", "summary": "未见异常" } ] }
 * }</pre>
 * {@code file} 可写全路径或纯文件名；未在 items 里出现的条目照样收，只是退回文件名解析。</li>
 * <li><b>文件名</b>：{@code <站点>-<日期>.<ext>}，站点认 code / 枚举名 / 中文名 / 别名，
 * 日期认 {@code yyyy-MM-dd} 与 {@code yyyyMMdd}；认不出站点 → {@link WebVerifySite#OTHER}，
 * 认不出日期 → 退回该 zip 条目的修改时间，再退回导入时刻（三级都是真实时间，不编）。</li>
 * </ol>
 *
 * <p>规模与安全护栏（全部<b>报错</b>，绝不静默截断）：见 {@link Limits}；路径含 {@code ..}
 * 或是绝对路径一律拒收（zip slip）。声明大小与实际读出的字节都要过闸——声明值是包里写的，会撒谎。
 */
@Component
@Slf4j
public class ManualWebVerifyProvider implements WebVerifyProvider {

    public static final String ID = "manual";
    public static final String MANIFEST_NAME = "manifest.json";

    /**
     * 规模上限。做成入参是为了让单测能用几十字节的包验到超限分支——
     * 按生产值造一个 512 MB 的 zip 只会把 CI 拖垮，验不到任何多余的东西。
     *
     * @param maxEntries    条目数上限（不含目录与 __MACOSX/.DS_Store 噪音、不含 manifest.json）
     * @param maxTotalBytes 解压后总字节上限
     * @param maxEntryBytes 单条目解压后字节上限
     * @param maxArchiveBytes zip 包本身的字节上限（整包载入内存）
     */
    public record Limits(int maxEntries, long maxTotalBytes, long maxEntryBytes, long maxArchiveBytes) {
        public static final Limits DEFAULT =
                new Limits(500, 512L * 1024 * 1024, 64L * 1024 * 1024, 256L * 1024 * 1024);
    }

    /** rawText 入内存的上限，超出截断（它只是给面板/模型看的文本，落盘的仍是完整字节）。 */
    private static final int RAW_TEXT_MAX = 200_000;

    /** 按扩展名判定「这是页面文本」，据此填 rawText。 */
    private static final Set<String> TEXT_EXTS = Set.of("txt", "html", "htm", "md", "json", "csv", "xml");

    private static final Pattern DATE_DASH = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DATE_COMPACT = Pattern.compile("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)");

    private final ObjectMapper objectMapper;
    private final Limits limits;

    @Autowired
    public ManualWebVerifyProvider(ObjectMapper objectMapper) {
        this(objectMapper, Limits.DEFAULT);
    }

    ManualWebVerifyProvider(ObjectMapper objectMapper, Limits limits) {
        this.objectMapper = objectMapper;
        this.limits = limits;
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public List<WebVerifyResult> verify(WebVerifyRequest req) {
        if (req == null || !StringUtils.hasText(req.partyName())) {
            throw new IllegalArgumentException(LangText.of("主体名不能为空", "Party name must not be empty"));
        }
        byte[] zip = req.archiveBytes();
        if (zip == null || zip.length == 0) {
            throw new IllegalArgumentException(LangText.of(
                    "离线网核适配器需要外部工具导出的 zip（本产品不联网抓取）",
                    "The offline web-verify adapter needs a zip exported by an external tool (this product does not crawl)"));
        }
        if (zip.length > limits.maxArchiveBytes()) {
            throw new IllegalArgumentException(LangText.of("网核压缩包过大，上限 ", "Web-verify archive too large, limit ")
                    + humanSize(limits.maxArchiveBytes()));
        }

        Set<WebVerifySite> wanted = req.sites().isEmpty() ? Set.of() : new HashSet<>(req.sites());
        LocalDateTime importedAt = LocalDateTime.now();

        List<WebVerifyResult> out = new ArrayList<>();
        try (ZipFile zf = openZip(zip)) {
            // 先整包扫一遍拿 manifest（条目顺序不保证 manifest 在最前）
            Map<String, ManifestItem> manifest = readManifest(zf);

            int entryCount = 0;
            long totalBytes = 0L;
            var en = zf.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                String path = normalizeEntryPath(decodeName(e));   // zip slip 在这里拒
                if (path == null || e.isDirectory()) continue;     // 目录与系统噪音（__MACOSX/.DS_Store）
                String baseName = fileNameOf(path);
                if (MANIFEST_NAME.equalsIgnoreCase(baseName)) continue;

                if (++entryCount > limits.maxEntries()) {
                    throw new IllegalArgumentException(LangText.of(
                            "网核压缩包条目过多，上限 ", "Too many entries in the web-verify archive, limit ") + limits.maxEntries()
                            + LangText.of(" 个；请拆成多个包分批导入", "; please split it into several archives"));
                }
                if (e.getSize() > limits.maxEntryBytes()) {        // 声明值先挡一道，省得白读
                    throw new IllegalArgumentException(entryTooLarge(path));
                }

                byte[] data = readCapped(zf, e, path);
                totalBytes += data.length;
                if (totalBytes > limits.maxTotalBytes()) {
                    throw new IllegalArgumentException(LangText.of(
                            "网核压缩包解压后总量超过上限 ", "Web-verify archive exceeds the total size limit of ")
                            + humanSize(limits.maxTotalBytes()));
                }

                ManifestItem mi = manifest.get(path);
                if (mi == null) mi = manifest.get(baseName);

                WebVerifySite site = mi != null && mi.site() != null ? mi.site() : WebVerifySite.parse(siteSegment(baseName));
                if (site == null) site = WebVerifySite.OTHER;
                if (!wanted.isEmpty() && !wanted.contains(site)) continue;

                LocalDateTime capturedAt = mi != null && mi.capturedAt() != null ? mi.capturedAt()
                        : parseDate(baseName, entryTime(e), importedAt);
                String ext = extOf(baseName);
                String rawText = mi != null && StringUtils.hasText(mi.text()) ? cap(mi.text())
                        : (TEXT_EXTS.contains(ext) ? cap(new String(data, StandardCharsets.UTF_8)) : null);

                out.add(new WebVerifyResult(site, capturedAt, landingName(site, capturedAt, ext), ext, data, null,
                        rawText, mi == null ? null : mi.summary(), mi == null ? null : mi.sourceUrl()));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("网核压缩包解析失败", e);
            throw new IllegalArgumentException(LangText.of(
                    "网核压缩包解析失败：文件可能已损坏或不是 zip",
                    "Failed to parse the web-verify archive: it may be corrupted or not a zip"), e);
        }
        return out;
    }

    // ------------------------------------------------------------------ 命名与解析（纯函数，单测直接打）

    /** {@code <站点>-<日期>.<ext>}，与 spec §1.2「网核截图 = ProjectFile」的命名一致。 */
    static String landingName(WebVerifySite site, LocalDateTime capturedAt, String ext) {
        String date = capturedAt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return site.label() + "-" + date + (StringUtils.hasText(ext) ? "." + ext : "");
    }

    /** 从文件名里切出站点段：去扩展名、去日期、去首尾分隔符。 */
    static String siteSegment(String fileName) {
        String s = stripExt(fileName);
        s = DATE_DASH.matcher(s).replaceAll("");
        s = DATE_COMPACT.matcher(s).replaceAll("");
        return s.replaceAll("^[-_\\s]+", "").replaceAll("[-_\\s]+$", "").trim();
    }

    /** 认 ISO-8601（带/不带时区）与 {@code yyyy-MM-dd}；认不出返回 null（不编时间）。 */
    static LocalDateTime parseIsoDateTime(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) { /* 下一种 */ }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) { /* 下一种 */ }
        try {
            return LocalDate.parse(s).atStartOfDay();
        } catch (Exception ignored) { /* 认不出 */ }
        return null;
    }

    /** 文件名里的日期 → zip 条目修改时间 → 导入时刻，三级兜底。 */
    static LocalDateTime parseDate(String fileName, LocalDateTime entryTime, LocalDateTime fallback) {
        Matcher m = DATE_DASH.matcher(fileName);
        if (m.find()) {
            LocalDate d = safeDate(m.group(1), m.group(2), m.group(3));
            if (d != null) return d.atStartOfDay();
        }
        m = DATE_COMPACT.matcher(fileName);
        if (m.find()) {
            LocalDate d = safeDate(m.group(1), m.group(2), m.group(3));
            if (d != null) return d.atStartOfDay();
        }
        return entryTime != null ? entryTime : fallback;
    }

    /**
     * 规范化条目路径：统一分隔符、去空段；含 {@code ..} 或是绝对路径直接拒收（zip slip）。
     * 返回 null 表示应跳过（{@code __MACOSX} / {@code .DS_Store} / 空路径）。
     *
     * <p>与 {@code ProjectFileService.normalizeEntryPath} 同一套判据、各自带测试；这里另外挡了盘符式
     * 绝对路径（{@code C:\…}），因为网核包多半来自 Windows 上的外部工具。
     */
    static String normalizeEntryPath(String raw) {
        if (raw == null) return null;
        String p = raw.replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:/.*")) throw new IllegalArgumentException(zipSlip(raw));
        List<String> segs = new ArrayList<>();
        for (String s : p.split("/")) {
            String seg = s.trim();
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) throw new IllegalArgumentException(zipSlip(raw));
            segs.add(seg);
        }
        if (segs.isEmpty()) return null;
        if ("__MACOSX".equals(segs.get(0)) || ".DS_Store".equals(segs.get(segs.size() - 1))) return null;
        return String.join("/", segs);
    }

    // ------------------------------------------------------------------ manifest

    /** manifest 里的一条；按全路径与纯文件名两种 key 建索引，两种写法都认。 */
    private record ManifestItem(WebVerifySite site, LocalDateTime capturedAt, String sourceUrl, String text, String summary) {}

    private Map<String, ManifestItem> readManifest(ZipFile zf) {
        Map<String, ManifestItem> out = new LinkedHashMap<>();
        ZipArchiveEntry entry = null;
        var en = zf.getEntries();
        while (en.hasMoreElements()) {
            ZipArchiveEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String path = normalizeEntryPath(decodeName(e));
            if (path == null) continue;
            if (MANIFEST_NAME.equalsIgnoreCase(fileNameOf(path))) { entry = e; break; }
        }
        if (entry == null) return out;
        try (InputStream in = zf.getInputStream(entry)) {
            JsonNode root = objectMapper.readTree(in.readNBytes(4 * 1024 * 1024));
            JsonNode items = root == null ? null : root.get("items");
            if (items == null || !items.isArray()) return out;
            for (JsonNode it : items) {
                String file = text(it, "file");
                if (!StringUtils.hasText(file)) continue;
                String norm = normalizeEntryPath(file);
                if (norm == null) continue;
                ManifestItem mi = new ManifestItem(
                        WebVerifySite.parse(text(it, "site")),
                        parseIsoDateTime(text(it, "capturedAt")),
                        text(it, "sourceUrl"),
                        text(it, "text"),
                        text(it, "summary"));
                out.put(norm, mi);
                out.putIfAbsent(fileNameOf(norm), mi);
            }
        } catch (IllegalArgumentException e) {
            throw e;   // manifest 里写了穿越路径，与条目里写了同等对待
        } catch (Exception e) {
            // manifest 本身坏了不该让整包进不来：退回文件名解析，但要留痕
            log.warn("网核 manifest.json 解析失败，退回按文件名解析", e);
            return new LinkedHashMap<>();
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    // ------------------------------------------------------------------ zip 读取

    private byte[] readCapped(ZipFile zf, ZipArchiveEntry e, String path) throws Exception {
        try (InputStream in = zf.getInputStream(e)) {
            // 多读 1 字节：读得出来就说明超限（包里声明的 size 会撒谎，只能按实读判）
            byte[] data = in.readNBytes((int) Math.min(limits.maxEntryBytes() + 1, Integer.MAX_VALUE));
            if (data.length > limits.maxEntryBytes()) throw new IllegalArgumentException(entryTooLarge(path));
            return data;
        }
    }

    private String entryTooLarge(String path) {
        return LangText.of("网核压缩包内单个文件过大（上限 ", "A file inside the web-verify archive is too large (limit ")
                + humanSize(limits.maxEntryBytes()) + LangText.of("）: ", "): ") + path;
    }

    private static String zipSlip(String raw) {
        return LangText.of("网核压缩包含非法路径（疑似路径穿越）: ",
                "The web-verify archive contains an invalid path (path traversal): ") + raw;
    }

    private static ZipFile openZip(byte[] bytes) throws java.io.IOException {
        return ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes))
                .setCharset(StandardCharsets.UTF_8)
                .get();
    }

    /**
     * 条目名解码：带 UTF-8(EFS) 标志位的直接用；否则按原始字节做严格 UTF-8 → 严格 GBK 的启发式
     * （macOS zip 写 UTF-8 名不设标志位，旧版 Windows 写 GBK 名）。都失败就保留宽松解码结果。
     */
    static String decodeName(ZipArchiveEntry e) {
        byte[] raw = e.getRawName();
        if (raw == null || (e.getGeneralPurposeBit() != null && e.getGeneralPurposeBit().usesUTF8ForNames())) {
            return e.getName();
        }
        for (Charset cs : new Charset[]{StandardCharsets.UTF_8, Charset.forName("GBK")}) {
            try {
                return cs.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException ignored) { /* 下一个编码 */ }
        }
        return e.getName();
    }

    // ------------------------------------------------------------------ 小工具

    static String fileNameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String stripExt(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(0, i) : fileName;
    }

    static String extOf(String fileName) {
        int i = fileName.lastIndexOf('.');
        if (i <= 0 || i == fileName.length() - 1) return "";
        String ext = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        return ext.length() > 32 ? ext.substring(0, 32) : ext;
    }

    private static LocalDate safeDate(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime entryTime(ZipArchiveEntry e) {
        long t = e.getTime();
        return t <= 0 ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault());
    }

    /** 上限文案：够一兆按 MB 说，不够就按字节说——测试里的小上限也要读得懂。 */
    private static String humanSize(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes / 1024 / 1024) + " MB" : bytes + LangText.of(" 字节", " bytes");
    }

    private static String cap(String s) {
        if (s == null) return null;
        return s.length() > RAW_TEXT_MAX ? s.substring(0, RAW_TEXT_MAX) : s;
    }
}
