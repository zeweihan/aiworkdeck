package com.checkba.service.evidence.webverify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线网核适配器：zip slip 拒收、三类超限拒收且报错清楚、manifest 与文件名两条解析路径、
 * 站点筛选、rawText/摘要取值。这里不碰数据库——落盘与挂链在 WebVerifyImportServiceTest 里锁。
 */
class ManualWebVerifyProviderTest {

    private final ObjectMapper om = new ObjectMapper();

    private ManualWebVerifyProvider provider() {
        return new ManualWebVerifyProvider(om);
    }

    private ManualWebVerifyProvider provider(ManualWebVerifyProvider.Limits limits) {
        return new ManualWebVerifyProvider(om, limits);
    }

    // ------------------------------------------------------------------ 造包

    /** 按 name → 字节造一个 zip；name 原样写进条目名（好造穿越路径）。 */
    private static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipArchiveEntry ze = new ZipArchiveEntry(e.getKey());
                ze.setSize(e.getValue().length);
                zos.putArchiveEntry(ze);
                zos.write(e.getValue());
                zos.closeArchiveEntry();
            }
            zos.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, byte[]> entries(Object... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof byte[] b ? b : ((String) v).getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    private static WebVerifyRequest req(byte[] zip, WebVerifySite... sites) {
        return new WebVerifyRequest("某某科技有限公司", "91110000MA000000XA", List.of(sites), zip);
    }

    // ------------------------------------------------------------------ zip slip

    @Test
    @DisplayName("zip slip：条目名含 .. 的包整包拒收，报错点名路径穿越")
    void rejectsZipSlip() {
        byte[] z = zip(entries("../../etc/passwd", "x", "裁判文书-2026-08-21.png", "y"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> provider().verify(req(z)));
        assertTrue(e.getMessage().contains("路径穿越"), e.getMessage());
    }

    @Test
    @DisplayName("zip slip：绝对路径（/ 开头与盘符）同样拒收")
    void rejectsAbsolutePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> provider().verify(req(zip(entries("/etc/passwd", "x")))));
        assertThrows(IllegalArgumentException.class,
                () -> provider().verify(req(zip(entries("C:\\Windows\\System32\\drivers\\etc\\hosts", "x")))));
    }

    @Test
    @DisplayName("zip slip：manifest 里写穿越路径也拒收，不能从 manifest 绕过")
    void rejectsZipSlipInManifest() {
        String manifest = "{\"items\":[{\"file\":\"../../evil.png\",\"site\":\"judgment_docs\"}]}";
        byte[] z = zip(entries("manifest.json", manifest, "a.png", "x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> provider().verify(req(z)));
        assertTrue(e.getMessage().contains("路径穿越"), e.getMessage());
    }

    // ------------------------------------------------------------------ 超限

    @Test
    @DisplayName("条目数超限：明确报错并说出上限，不静默截断")
    void rejectsTooManyEntries() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) m.put("裁判文书-2026-08-2" + i + ".png", ("x" + i).getBytes(StandardCharsets.UTF_8));
        ManualWebVerifyProvider.Limits limits = new ManualWebVerifyProvider.Limits(3, 1024, 1024, 1024 * 1024);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider(limits).verify(req(zip(m))));
        assertTrue(e.getMessage().contains("条目过多"), e.getMessage());
        assertTrue(e.getMessage().contains("3"), e.getMessage());
    }

    @Test
    @DisplayName("单条目超限：明确报错并点名是哪个文件")
    void rejectsOversizedEntry() {
        byte[] big = new byte[64];
        byte[] z = zip(entries("裁判文书-2026-08-21.png", big));
        ManualWebVerifyProvider.Limits limits = new ManualWebVerifyProvider.Limits(10, 1024 * 1024, 32, 1024 * 1024);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider(limits).verify(req(z)));
        assertTrue(e.getMessage().contains("单个文件过大"), e.getMessage());
        assertTrue(e.getMessage().contains("裁判文书-2026-08-21.png"), e.getMessage());
    }

    @Test
    @DisplayName("单条目超限：包里声明的 size 撒谎也要按实读拦住")
    void rejectsOversizedEntryEvenWhenDeclaredSizeLies() throws Exception {
        // 手工造一个「声明 1 字节、实际 64 字节」的条目
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            ZipArchiveEntry ze = new ZipArchiveEntry("失信被执行人-2026-08-21.png");
            zos.putArchiveEntry(ze);          // 不 setSize，让它走 data descriptor
            zos.write(new byte[64]);
            zos.closeArchiveEntry();
            zos.finish();
        }
        ManualWebVerifyProvider.Limits limits = new ManualWebVerifyProvider.Limits(10, 1024 * 1024, 32, 1024 * 1024);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider(limits).verify(req(bos.toByteArray())));
        assertTrue(e.getMessage().contains("单个文件过大"), e.getMessage());
    }

    @Test
    @DisplayName("解压后总量超限：明确报错并说出上限")
    void rejectsOversizedTotal() {
        Map<String, byte[]> m = entries(
                "裁判文书-2026-08-21.png", new byte[40],
                "失信被执行人-2026-08-21.png", new byte[40]);
        ManualWebVerifyProvider.Limits limits = new ManualWebVerifyProvider.Limits(10, 50, 1024, 1024 * 1024);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider(limits).verify(req(zip(m))));
        assertTrue(e.getMessage().contains("总量超过上限"), e.getMessage());
    }

    @Test
    @DisplayName("整包超限：连解都不解，直接报错")
    void rejectsOversizedArchive() {
        byte[] z = zip(entries("裁判文书-2026-08-21.png", new byte[100]));
        ManualWebVerifyProvider.Limits limits = new ManualWebVerifyProvider.Limits(10, 1024, 1024, 10);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider(limits).verify(req(z)));
        assertTrue(e.getMessage().contains("压缩包过大"), e.getMessage());
    }

    // ------------------------------------------------------------------ 两条解析路径

    @Test
    @DisplayName("文件名解析：<站点>-<日期>.<ext>，站点认中文名/别名，日期认两种写法")
    void parsesByFileName() {
        byte[] z = zip(entries(
                "企业信用信息公示-2026-08-21.png", "a",
                "gsxt-20260820.png", "b",
                "裁判文书-2026-08-19.png", "c"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals(3, out.size());
        assertEquals(WebVerifySite.CREDIT_PUBLICITY, out.get(0).site());
        assertEquals(LocalDateTime.of(2026, 8, 21, 0, 0), out.get(0).queriedAt());
        assertEquals("企业信用信息公示-2026-08-21.png", out.get(0).fileName());
        assertEquals(WebVerifySite.CREDIT_PUBLICITY, out.get(1).site());
        assertEquals(LocalDateTime.of(2026, 8, 20, 0, 0), out.get(1).queriedAt());
        assertEquals("企业信用信息公示-2026-08-20.png", out.get(1).fileName(), "落盘名按站点中文名归一，不留外部工具的缩写");
        assertEquals(WebVerifySite.JUDGMENT_DOCS, out.get(2).site());
    }

    @Test
    @DisplayName("文件名解析：认不出的站点是 OTHER，认不出的日期退回条目时间，都不报错")
    void unknownSiteBecomesOther() {
        byte[] z = zip(entries("随手存的一张图.png", "a"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals(1, out.size());
        assertEquals(WebVerifySite.OTHER, out.get(0).site());
        assertNotNull(out.get(0).queriedAt());
    }

    @Test
    @DisplayName("manifest 解析：站点/时间/URL/文本/摘要都来自 manifest，覆盖文件名的判断")
    void parsesByManifest() {
        String manifest = """
                { "provider": "someExternalTool", "party": "某某科技有限公司",
                  "items": [
                    { "file": "shot1.png", "site": "dishonest_executee",
                      "capturedAt": "2026-08-21T10:00:00+08:00",
                      "sourceUrl": "https://example.gov.cn/q?name=x",
                      "text": "页面文本若干", "summary": "未见失信记录" } ] }
                """;
        byte[] z = zip(entries("manifest.json", manifest, "shot1.png", "binary"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals(1, out.size());
        WebVerifyResult r = out.get(0);
        assertEquals(WebVerifySite.DISHONEST_EXECUTEE, r.site());
        assertEquals(LocalDateTime.of(2026, 8, 21, 10, 0), r.queriedAt());
        assertEquals("https://example.gov.cn/q?name=x", r.sourceUrl());
        assertEquals("页面文本若干", r.rawText());
        assertEquals("未见失信记录", r.summary());
        assertEquals("失信被执行人-2026-08-21.png", r.fileName());
    }

    @Test
    @DisplayName("manifest 的 capturedAt 按字面取，不随服务端时区换算（本机 +08 与 CI UTC 必须同解）")
    void capturedAtIsZoneIndependent() {
        LocalDateTime expected = LocalDateTime.of(2026, 8, 21, 10, 0);
        assertEquals(expected, ManualWebVerifyProvider.parseIsoDateTime("2026-08-21T10:00:00+08:00"));
        assertEquals(expected, ManualWebVerifyProvider.parseIsoDateTime("2026-08-21T10:00:00Z"));
        assertEquals(expected, ManualWebVerifyProvider.parseIsoDateTime("2026-08-21T10:00:00-05:00"));
        assertEquals(expected, ManualWebVerifyProvider.parseIsoDateTime("2026-08-21T10:00:00"));
    }

    @Test
    @DisplayName("manifest 只覆盖它列到的条目，没列到的仍按文件名解析")
    void manifestCoversOnlyListedEntries() {
        String manifest = "{\"items\":[{\"file\":\"shot1.png\",\"site\":\"env_penalty\",\"capturedAt\":\"2026-08-01\"}]}";
        byte[] z = zip(entries("manifest.json", manifest,
                "shot1.png", "a",
                "裁判文书-2026-08-19.png", "b"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals(2, out.size());
        assertEquals(WebVerifySite.ENV_PENALTY, out.get(0).site());
        assertEquals(WebVerifySite.JUDGMENT_DOCS, out.get(1).site());
    }

    @Test
    @DisplayName("manifest 坏了不让整包进不来：退回文件名解析")
    void brokenManifestFallsBackToFileNames() {
        byte[] z = zip(entries("manifest.json", "{ 这不是 json", "裁判文书-2026-08-19.png", "b"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals(1, out.size());
        assertEquals(WebVerifySite.JUDGMENT_DOCS, out.get(0).site());
    }

    @Test
    @DisplayName("文本件的 rawText 取自身内容；截图件没有 manifest 文本时 rawText 为空")
    void rawTextFromTextEntries() {
        byte[] z = zip(entries("裁判文书-2026-08-19.txt", "全文若干", "裁判文书-2026-08-19.png", "PNG-BYTES"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals("全文若干", out.get(0).rawText());
        assertNull(out.get(1).rawText());
    }

    // ------------------------------------------------------------------ 站点筛选与噪音

    @Test
    @DisplayName("sites 非空时只收指定站点，其余跳过")
    void filtersBySites() {
        byte[] z = zip(entries("裁判文书-2026-08-19.png", "a", "企业信用信息公示-2026-08-21.png", "b"));
        List<WebVerifyResult> out = provider().verify(req(z, WebVerifySite.JUDGMENT_DOCS));
        assertEquals(1, out.size());
        assertEquals(WebVerifySite.JUDGMENT_DOCS, out.get(0).site());
    }

    @Test
    @DisplayName("__MACOSX 与 .DS_Store 跳过，不占条目数也不落盘")
    void skipsSystemNoise() {
        byte[] z = zip(entries(
                "__MACOSX/._shot.png", "junk",
                ".DS_Store", "junk",
                "裁判文书-2026-08-19.png", "a"));
        List<WebVerifyResult> out = provider().verify(req(z));
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("没有 zip 就报「本产品不联网抓取」，不去联网")
    void requiresArchive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider().verify(new WebVerifyRequest("某某科技有限公司", null, List.of(), null)));
        assertTrue(e.getMessage().contains("不联网抓取"), e.getMessage());
    }

    @Test
    @DisplayName("主体名为空直接拒")
    void requiresPartyName() {
        assertThrows(IllegalArgumentException.class,
                () -> provider().verify(new WebVerifyRequest(" ", null, List.of(), zip(entries("a.png", "x")))));
    }

    @Test
    @DisplayName("适配器标识是 manual，会写进落盘文件的 metaJson.provider")
    void providerId() {
        assertEquals("manual", provider().providerId());
    }
}
