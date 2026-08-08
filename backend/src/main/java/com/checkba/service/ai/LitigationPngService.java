package com.checkba.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诉讼图的 SVG → PNG 光栅化。
 *
 * <p><b>为什么非要有位图：</b>{@code doc_insert_image} 只收 jpg/png/gif/bmp/webp，
 * 不收 svg。没有 PNG，画好的图就插不进用户正在写的起诉状——而那正是这个功能的
 * 主线工作流。SVG 作为母版在编辑器和浏览器里显示得很好，但它进不了文书。
 *
 * <p><b>为什么用 Batik 而不是外部光栅器：</b>rsvg-convert / inkscape / soffice /
 * cairosvg 都要装原生程序，桌面端一个都不随包分发。靠它们意味着「大多数用户机器上
 * 根本没有 PNG」——引擎会静默跳过，用户只看到说好的文件少了一个。Batik 是纯 Java，
 * 随 jar 走，任何装了本产品的机器上行为一致。
 *
 * <p><b>中文怎么保证不出豆腐块：</b>见 {@link #ensureFontsRegistered()} 与
 * {@link #withFallbackFonts(String)} 的注释。一句话：不指望系统字体，把随包字体
 * 注册进 JVM，并在**内存里**给字体栈追加末位兜底——磁盘上的 SVG 母版一个字节不动。
 */
@Service
@Slf4j
public class LitigationPngService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LitigationPngService.class);

    /** 输出宽度。A4 横向 300dpi 约 3500px；1600 够清晰又不至于让 docx 体积失控。 */
    private static final float TARGET_WIDTH = 1600f;

    /** 随包字体文件名 → 期望的家族名。文件来自 desktop/scripts/fetch-lowa-assets.js（都是 OFL）。 */
    private static final String SERIF_FILE = "cjk-serif.otf";   // Noto Serif SC
    private static final String SANS_FILE = "cjk.ttc";          // Noto Sans SC

    private static final Pattern FONT_FAMILY = Pattern.compile("font-family=\"([^\"]*)\"");

    @Value("${litviz.font-dir:}")
    private String configuredFontDir;

    private volatile Boolean fontsReady;
    private volatile String serifFamily;
    private volatile String sansFamily;

    // ==================== 字体 ====================

    /**
     * 把随包的中文字体注册进 JVM。
     *
     * <p>不能指望系统字体：SVG 标题的字体栈首选「方正小标宋简体」等商业字体，
     * 一台干净的 Windows 上一个都没有，落到通用 serif 就是 Times——不含任何汉字，
     * 整行标题变豆腐块。正文用的 sans 栈里有 Microsoft YaHei 所以看起来正常，
     * 于是这个 bug 只坏标题、特别容易漏掉（上游同样的坑见 litviz/PATCHES.md 的 PATCH 1）。
     *
     * <p>注册成功后 {@link #withFallbackFonts} 会把家族名追加进字体栈末位：
     * 有更好字体的机器上外观不变，没有的机器上也不会豆腐块。
     */
    private synchronized void ensureFontsRegistered() {
        if (fontsReady != null) return;
        Path dir = resolveFontDir();
        if (dir == null) {
            log.warn("找不到随包中文字体目录，PNG 里的中文将依赖系统字体（可能出现豆腐块）");
            fontsReady = Boolean.FALSE;
            return;
        }
        serifFamily = registerOne(dir.resolve(SERIF_FILE));
        sansFamily = registerOne(dir.resolve(SANS_FILE));
        fontsReady = (serifFamily != null || sansFamily != null);
        log.info("诉讼图 PNG 字体：serif={} sans={} (dir={})", serifFamily, sansFamily, dir);
    }

    private String registerOne(Path file) {
        if (!Files.isRegularFile(file)) {
            log.warn("随包字体缺失：{}", file);
            return null;
        }
        try {
            // .ttc 里可能有多个字体，createFonts 全取；家族名以第一个为准
            Font[] fonts = Font.createFonts(file.toFile());
            if (fonts.length == 0) return null;
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (Font f : fonts) ge.registerFont(f);
            return fonts[0].getFamily();
        } catch (Exception e) {
            log.warn("注册随包字体失败：{}", file, e);
            return null;
        }
    }

    /**
     * 字体目录定位：显式配置 → 环境变量 → 相对后端工作目录找 frontend/dist/zetaoffice。
     * 与 litviz 引擎共用 {@code LITVIZ_FONT_DIR}（Python 侧的 PATCH 1 也读这个变量）。
     */
    private Path resolveFontDir() {
        for (String c : new String[]{configuredFontDir, System.getenv("LITVIZ_FONT_DIR")}) {
            if (c != null && !c.isBlank()) {
                Path p = Paths.get(c).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) return p;
            }
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        for (Path base : new Path[]{cwd, cwd.getParent()}) {
            if (base == null) continue;
            Path p = base.resolve("frontend").resolve("dist").resolve("zetaoffice");
            if (Files.isDirectory(p)) return p.normalize();
        }
        return null;
    }

    /**
     * 给 SVG 里每条字体栈追加我们注册的家族作为**末位**兜底。
     *
     * <p>追加而不是替换：机器上真装了方正小标宋的律师，出来的图应该还是他那款字；
     * 我们只负责保证「一个都没有时不至于变豆腐块」。插在通用关键字
     * （serif / sans-serif）之前，因为通用关键字一旦命中就不会再往后找了。
     *
     * <p>只改内存里这一份，磁盘上的 SVG 母版不动——那是交付物，不该因为我们要出
     * 一张预览图而被改写。
     */
    String withFallbackFonts(String svg) {
        if (serifFamily == null && sansFamily == null) return svg;
        Matcher m = FONT_FAMILY.matcher(svg);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String stack = m.group(1);
            boolean isSans = stack.contains("sans-serif");
            String want = isSans ? sansFamily : serifFamily;
            String rewritten = stack;
            if (want != null && !stack.contains("'" + want + "'") && !stack.contains(want)) {
                String generic = isSans ? "sans-serif" : "serif";
                int idx = stack.lastIndexOf(generic);
                rewritten = idx >= 0
                        ? stack.substring(0, idx) + "'" + want + "'," + stack.substring(idx)
                        : stack + ",'" + want + "'";
            }
            m.appendReplacement(out, Matcher.quoteReplacement("font-family=\"" + rewritten + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ==================== 光栅化 ====================

    /**
     * 把一个 SVG 转成同目录同名的 PNG。
     *
     * @return 生成的 PNG 路径；失败返回 null（出图整体不该因为少一张预览位图而失败）
     */
    public Path rasterize(Path svg) {
        return rasterize(svg, svg.resolveSibling(stripExt(svg.getFileName().toString()) + ".png"));
    }

    public Path rasterize(Path svg, Path png) {
        if (svg == null || !Files.isRegularFile(svg)) return null;
        ensureFontsRegistered();
        try {
            String body = withFallbackFonts(Files.readString(svg, StandardCharsets.UTF_8));
            PNGTranscoder t = new PNGTranscoder();
            t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, TARGET_WIDTH);
            Files.createDirectories(png.getParent());
            try (OutputStream out = Files.newOutputStream(png)) {
                t.transcode(new TranscoderInput(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),
                        new TranscoderOutput(out));
            }
            if (!Files.isRegularFile(png) || Files.size(png) == 0) {
                Files.deleteIfExists(png);
                return null;
            }
            return png;
        } catch (Exception e) {
            log.warn("SVG 转 PNG 失败：{}", svg, e);
            try { Files.deleteIfExists(png); } catch (Exception ignored) { }
            return null;
        }
    }

    /**
     * 给一批引擎产物补上 PNG：产物里已经有 PNG（机器上装了光栅器）就不重复做。
     *
     * @return 新生成的 PNG 路径列表（通常 0 或 1 个）
     */
    public List<Path> ensurePngFor(List<Path> artifacts) {
        List<Path> added = new ArrayList<>();
        Set<String> bases = new LinkedHashSet<>();
        boolean hasPng = false;
        for (Path p : artifacts) {
            String n = p.getFileName().toString();
            if (n.endsWith(".png")) hasPng = true;
            // .drawio.svg 是带内嵌模型的副本，不是母版，不拿它出图
            if (n.endsWith(".svg") && !n.endsWith(".drawio.svg")) bases.add(p.toString());
        }
        if (hasPng) return added;
        for (String svg : bases) {
            Path png = rasterize(Paths.get(svg));
            if (png != null) added.add(png);
        }
        return added;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    /** 诊断用：字体是否就绪、家族名各是什么。 */
    public String fontStatus() {
        ensureFontsRegistered();
        if (!Boolean.TRUE.equals(fontsReady)) return "未注册随包中文字体，将依赖系统字体";
        return "serif=" + serifFamily + " sans=" + sansFamily;
    }

    /** 测试 seam：某款随包字体能否显示给定文本（-1 = 全部可显示）。 */
    int canDisplayUpTo(boolean serif, String text) {
        ensureFontsRegistered();
        String fam = serif ? serifFamily : sansFamily;
        if (fam == null) return 0;
        return new Font(fam, Font.PLAIN, 12).canDisplayUpTo(text);
    }
}
