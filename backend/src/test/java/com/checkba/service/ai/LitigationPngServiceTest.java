package com.checkba.service.ai;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SVG → PNG 光栅化。
 *
 * <p>断言的是**机制**，不是像素：字体真的注册进来了、它真的覆盖我们要画的汉字、
 * 字体栈真的被追加了、产物真的是一张能解码的图。
 * 「看起来对不对」用像素比对来守是脆的——抗锯齿、字体版本、平台差异都会让它无故变红。
 */
class LitigationPngServiceTest {

    private static LitigationPngService svc;
    private static Path fontDir;

    @BeforeAll
    static void setUp() {
        svc = new LitigationPngService();
        fontDir = locateFontDir();
        ReflectionTestUtils.setField(svc, "configuredFontDir", fontDir == null ? "" : fontDir.toString());
    }

    /** 测试跑在 backend/ 下，字体在 ../frontend/dist/zetaoffice（需先跑过 fetch-lowa-assets）。 */
    private static Path locateFontDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path base : new Path[]{cwd, cwd.getParent()}) {
            if (base == null) continue;
            Path p = base.resolve("frontend").resolve("dist").resolve("zetaoffice");
            if (Files.isRegularFile(p.resolve("cjk-serif.otf"))) return p;
        }
        return null;
    }

    private static void requireFonts() {
        assumeTrue(fontDir != null,
                "跳过：本机没有随包字体（frontend/dist/zetaoffice/cjk-serif.otf），"
                        + "需先跑 node desktop/scripts/fetch-lowa-assets.js");
    }

    // ==== 字体：这是「中文不出豆腐块」的全部依据 ====

    @Test
    @DisplayName("随包宋体覆盖诉讼图里的汉字——不指望用户机器上装了方正小标宋")
    void bundledSerifCoversChinese() {
        requireFonts();
        // -1 = 每个字符都能显示。SVG 标题的字体栈首选商业字体（方正小标宋简体），
        // 干净的 Windows 上一个都没有，落到通用 serif 就是 Times——不含汉字。
        // 我们兜底的这款必须真的覆盖，否则整行标题是豆腐块。
        assertEquals(-1, svc.canDisplayUpTo(true, "担保纠纷案件事实经过时间轴"),
                "随包宋体应能显示标题里的每个汉字");
        assertEquals(-1, svc.canDisplayUpTo(true, "诉讼时效保证期间债权人债务人"),
                "常见诉讼术语也要全覆盖");
    }

    @Test
    @DisplayName("随包黑体覆盖正文汉字")
    void bundledSansCoversChinese() {
        requireFonts();
        assertEquals(-1, svc.canDisplayUpTo(false, "甲邮寄催款函，乙签收，丙拒收"));
    }

    // ==== 字体栈重写 ====
    //
    // 这一组不依赖字体文件：直接把家族名注进去测纯字符串逻辑。
    // 否则整组会在没跑过 fetch-lowa-assets 的机器（含 CI 的后端测试 job）上被 skip，
    // 等于这段最容易写错的逻辑根本没人守。

    private static LitigationPngService rewriterWithFamilies() {
        LitigationPngService s = new LitigationPngService();
        ReflectionTestUtils.setField(s, "serifFamily", "Noto Serif SC");
        ReflectionTestUtils.setField(s, "sansFamily", "Noto Sans SC");
        ReflectionTestUtils.setField(s, "fontsReady", Boolean.TRUE);
        return s;
    }

    @Test
    @DisplayName("兜底字体追加在通用关键字之前——通用关键字一旦命中就不再往后找")
    void appendsFallbackBeforeGenericKeyword() {
        LitigationPngService svc = rewriterWithFamilies();
        String svg = "<text font-family=\"'方正小标宋简体','华文中宋',serif\">图</text>";
        String out = svc.withFallbackFonts(svg);

        assertTrue(out.contains("Noto Serif SC"), "应追加随包宋体：" + out);
        int fallbackAt = out.indexOf("Noto Serif SC");
        int genericAt = out.lastIndexOf("serif\"");
        assertTrue(fallbackAt < genericAt, "兜底必须排在通用 serif 之前，否则永远轮不到它：" + out);
        assertTrue(out.contains("方正小标宋简体"),
                "原有首选必须保留——机器上真装了这款字的律师应该还是得到他那款");
    }

    @Test
    @DisplayName("sans 栈追加的是黑体不是宋体")
    void picksSansFallbackForSansStack() {
        String out = rewriterWithFamilies().withFallbackFonts(
                "<text font-family=\"'PingFang SC','Helvetica Neue',Arial,sans-serif\">正文</text>");
        assertTrue(out.contains("Noto Sans SC"), out);
        assertFalse(out.contains("Noto Serif SC"), "sans 栈不该被塞进宋体：" + out);
    }

    @Test
    @DisplayName("已经含有兜底字体时不重复追加")
    void doesNotDuplicateExistingFallback() {
        String stack = "'PingFang SC','Noto Sans SC',sans-serif";
        String out = rewriterWithFamilies().withFallbackFonts("<text font-family=\"" + stack + "\">x</text>");
        int first = out.indexOf("Noto Sans SC");
        assertEquals(first, out.lastIndexOf("Noto Sans SC"), "不该出现两次：" + out);
    }

    // ==== 光栅化 ====

    @Test
    @DisplayName("Batik 接通了：不依赖任何字体也能出一张真图")
    void rasterisesWithoutAnyBundledFont() throws Exception {
        // 这条**不**调 requireFonts()：CI 的后端 job 不跑 fetch-lowa-assets，
        // 下面几条 CJK 用例会整组 skip。若连这条也 skip，就等于"Batik 到底接没接上"
        // 在 CI 里没有任何人守——依赖漏了、编码器缺了都要等真机才发现。
        Path dir = Files.createTempDirectory("png-test-");
        try {
            Path svg = dir.resolve("plain.svg");
            Files.writeString(svg, """
                    <svg xmlns="http://www.w3.org/2000/svg" width="400" height="200">
                      <rect x="10" y="10" width="380" height="180" fill="#991B1B"/>
                      <text x="40" y="110" font-size="28" fill="#ffffff">Litigation</text>
                    </svg>
                    """, StandardCharsets.UTF_8);
            Path png = svc.rasterize(svg, dir.resolve("plain.png"));
            assertNotNull(png, "光栅化不该失败");
            BufferedImage img = ImageIO.read(png.toFile());
            assertNotNull(img, "应是一张能解码的 PNG");
            assertEquals(1600, img.getWidth());
            // 中心必然落在那块深红矩形上——证明真画了内容，不是一张空白画布
            assertEquals(0x991B1B, img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF,
                    "中心像素应是图里那块深红");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    @DisplayName("真 SVG 转出一张能解码的 PNG，尺寸符合目标宽度")
    void rasterisesARealDiagram() throws Exception {
        requireFonts();
        Path svg = litvizExampleSvg();
        assumeTrue(svg != null, "跳过：本机没生成过示例 SVG");
        Path out = Files.createTempDirectory("png-test-").resolve("out.png");
        try {
            Path png = svc.rasterize(svg, out);
            assertNotNull(png, "光栅化不该失败");
            BufferedImage img = ImageIO.read(png.toFile());
            assertNotNull(img, "产物应是一张能解码的 PNG");
            assertEquals(1600, img.getWidth(), "宽度应为设定的目标值");
            assertTrue(img.getHeight() > 100, "高度应按原图比例推出来，不该是一条线");
        } finally {
            deleteTree(out.getParent());
        }
    }

    @Test
    @DisplayName("图不是一片空白——渲染真的画了东西")
    void outputIsNotBlank() throws Exception {
        requireFonts();
        Path svg = litvizExampleSvg();
        assumeTrue(svg != null, "跳过：本机没生成过示例 SVG");
        Path out = Files.createTempDirectory("png-test-").resolve("o.png");
        try {
            BufferedImage img = ImageIO.read(svc.rasterize(svg, out).toFile());
            long ink = 0;
            for (int y = 0; y < img.getHeight(); y += 3) {
                for (int x = 0; x < img.getWidth(); x += 3) {
                    int rgb = img.getRGB(x, y) & 0xFFFFFF;
                    if (rgb != 0xFFFFFF && rgb != 0) ink++;   // 既非纯白也非全透明
                }
            }
            // 只断言"画了不少东西"，不比对像素：抗锯齿与字体版本会让精确比对无故变红
            assertTrue(ink > 500, "有颜色的采样点太少（" + ink + "），大概率是空白图");
        } finally {
            deleteTree(out.getParent());
        }
    }

    @Test
    @DisplayName("坏输入返回 null 而不是抛异常——少一张预览图不该让整次出图失败")
    void badInputFailsSoft() throws Exception {
        Path dir = Files.createTempDirectory("png-test-");
        try {
            Path bad = dir.resolve("bad.svg");
            Files.writeString(bad, "这不是 SVG", StandardCharsets.UTF_8);
            assertNull(svc.rasterize(bad, dir.resolve("x.png")), "坏 SVG 应返回 null");
            assertNull(svc.rasterize(dir.resolve("不存在.svg"), dir.resolve("y.png")), "文件不存在应返回 null");
            assertFalse(Files.exists(dir.resolve("x.png")), "失败时不该留下半截文件");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    @DisplayName("产物里已经有 PNG 时不重复光栅化")
    void skipsWhenEngineAlreadyProducedPng() throws Exception {
        Path dir = Files.createTempDirectory("png-test-");
        try {
            Path svg = dir.resolve("a.svg");
            Path png = dir.resolve("a.png");
            Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>");
            Files.writeString(png, "existing");
            List<Path> added = svc.ensurePngFor(List.of(svg, png));
            assertTrue(added.isEmpty(), "已有 PNG 时不该再生成");
            assertEquals("existing", Files.readString(png), "既有 PNG 不该被覆盖");
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    @DisplayName("drawio 那份带内嵌模型的 svg 不参与出图")
    void ignoresTheDrawioSvgCopy() throws Exception {
        Path dir = Files.createTempDirectory("png-test-");
        try {
            Path drawioSvg = dir.resolve("a.drawio.svg");
            Files.writeString(drawioSvg, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>");
            assertTrue(svc.ensurePngFor(List.of(drawioSvg)).isEmpty(),
                    ".drawio.svg 是可编辑副本不是母版，不该拿它出 PNG");
        } finally {
            deleteTree(dir);
        }
    }

    // ==== 辅助 ====

    /** 用仓库里的 litviz 引擎现生成一张 SVG；引擎不可用则返回 null 让用例跳过。 */
    private static Path litvizExampleSvg() throws Exception {
        LitigationVisualService lv = new LitigationVisualService();
        ReflectionTestUtils.setField(lv, "configuredDir", "");
        ReflectionTestUtils.setField(lv, "configuredPython", "");
        ReflectionTestUtils.setField(lv, "configuredGraphvizDir", "");
        if (lv.unavailableReason() != null) return null;
        Path dir = Files.createTempDirectory("png-src-");
        Path map = lv.runtime().litvizDir().resolve("mqc-litigation-visual-redraw").resolve("examples").resolve("timeline-points.json");
        LitigationVisualService.Result r = lv.render(map, dir.resolve("d"), null, "svg");
        if (!r.ok()) return null;
        return Path.of(r.raw().getJSONArray("files").getJSONObject(0).getStr("path"));
    }

    private static void deleteTree(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) { }
            });
        }
    }
}
