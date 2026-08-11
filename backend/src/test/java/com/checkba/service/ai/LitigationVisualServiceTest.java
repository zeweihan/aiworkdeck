package com.checkba.service.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 诉讼可视化的进程边界测试——真起 Python、真跑引擎、真落盘。
 *
 * <p>刻意不 mock：这条链路的风险全在"环境"上（解释器版本、路径解析、编码、
 * 产物落点），mock 掉进程恰好把要守的东西守没了。引擎本身是纯计算，秒级返回，
 * 当整套单测的一部分跑得起。
 *
 * <p>环境不具备时（比如 CI 上没装 Python）整类跳过而不是红：litviz 是可降级能力，
 * 不该把没装 Python 的构建机变成失败构建。真正的把关在
 * {@code litviz/tests/test_cli.py} 与桌面打包冒烟里。
 */
class LitigationVisualServiceTest {

    private static LitigationVisualService svc;
    private static Path examples;

    @BeforeAll
    static void setUp() {
        svc = new LitigationVisualService();
        // @Value 字段在纯单测里不会被注入，显式置空 = 走自动探测（正是要测的那条路）
        ReflectionTestUtils.setField(svc, "configuredDir", "");
        ReflectionTestUtils.setField(svc, "configuredPython", "");
        ReflectionTestUtils.setField(svc, "configuredGraphvizDir", "");

        LitigationVisualService.Runtime rt = svc.runtime();
        if (rt.litvizDir() != null) {
            examples = rt.litvizDir().resolve("engine").resolve("examples");
        }
    }

    private static void requireRuntime() {
        assumeTrue(svc.unavailableReason() == null,
                "跳过：" + svc.unavailableReason());
    }

    // ==== 运行时定位 ====

    @Test
    @DisplayName("从后端工作目录能自动找到 litviz/（dev 态 cwd=backend/，命中 ../litviz）")
    void locatesLitvizFromBackendCwd() {
        LitigationVisualService.Runtime rt = svc.runtime();
        assertNotNull(rt.litvizDir(), "应能自动定位 litviz 目录；cwd=" + Paths.get("").toAbsolutePath());
        assertTrue(Files.isRegularFile(rt.litvizDir().resolve("cli.py")), "定位到的目录里应有 cli.py");
    }

    @Test
    @DisplayName("挑到的解释器不低于 3.11——低于此引擎在 import 期就 SyntaxError")
    void picksAnInterpreterNewEnough() {
        requireRuntime();
        String v = svc.runtime().pythonVersion();
        assertFalse(v.isBlank(), "应探到版本号");
        String[] p = v.split("\\.");
        int major = Integer.parseInt(p[0]);
        int minor = Integer.parseInt(p[1]);
        assertTrue(major > 3 || (major == 3 && minor >= 11), "解释器版本过低：" + v);
    }

    @Test
    @DisplayName("环境不具备时给的是人话，不是 NPE")
    void explainsItselfWhenUnavailable() {
        LitigationVisualService broken = new LitigationVisualService();
        ReflectionTestUtils.setField(broken, "configuredDir", "/definitely/not/here");
        ReflectionTestUtils.setField(broken, "configuredPython", "");
        ReflectionTestUtils.setField(broken, "configuredGraphvizDir", "");
        // configuredDir 无效会回落到自动探测，所以这里只断言"要么可用、要么给出说明"
        String why = broken.unavailableReason();
        if (why != null) {
            assertTrue(why.contains("litviz") || why.contains("Python"), "说明应指明缺什么：" + why);
        }
    }

    // ==== 出图 ====

    @Test
    @DisplayName("时间轴：矢量与可编辑源文件必出，PNG 视机器有无光栅器而定")
    void rendersTimelineWithAllFormats() throws Exception {
        requireRuntime();
        Path out = Files.createTempDirectory("litviz-test-");
        try {
            LitigationVisualService.Result r = svc.render(
                    examples.resolve("timeline-points.json"), out.resolve("时间轴"), "奇川风", null);

            assertTrue(r.ok(), "出图应成功：" + r.error() + "\n" + r.stderr());
            assertFalse(r.raw().getBool("draft", true), "已确认的地图不该是草稿");
            assertEquals("奇川风", r.raw().getStr("mode"));

            JSONArray files = r.raw().getJSONArray("files");
            java.util.Set<String> got = new java.util.HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                JSONObject f = files.getJSONObject(i);
                Path p = Path.of(f.getStr("path"));
                assertTrue(Files.isRegularFile(p), "报了但没落盘：" + p);
                assertTrue(f.getLong("bytes") > 0, "空文件：" + p);
                got.add(f.getStr("format"));
            }

            // 这三种是纯计算产物，任何机器上都必须有。pptx/vsdx 引擎仍会产出（这条用例
            // 直接调 svc.render 不经过 LitigationVisualTools.DEFAULT_FORMATS，走的是
            // cli.py 自己的默认值），但产品已不再交付这两种格式，不再断言。
            assertTrue(got.containsAll(java.util.Set.of("svg", "drawio", "drawio-svg")),
                    "缺少矢量母版或可编辑源文件：" + got);

            // PNG 要外部光栅器（rsvg-convert / inkscape / soffice / cairosvg），
            // 桌面端一个都不随包分发，CI 的 runner 上通常也没有。所以断言的是
            // 「与本机实际能力一致」，而不是一个写死的文件数——写死数字会让这条
            // 测试变成"构建机装了什么"的探针，在没装光栅器的机器上无故变红。
            boolean hasRasteriser = svc.doctor().raw().getBool("rasteriser", false);
            assertEquals(hasRasteriser, got.contains("png"),
                    hasRasteriser ? "本机有光栅器，PNG 却没出" : "本机没有光栅器，不该凭空出 PNG");
        } finally {
            deleteTree(out);
        }
    }

    @Test
    @DisplayName("SVG 母版里带中文，没有被编码成乱码")
    void svgKeepsChineseIntact() throws Exception {
        requireRuntime();
        Path out = Files.createTempDirectory("litviz-test-");
        try {
            LitigationVisualService.Result r = svc.render(
                    examples.resolve("timeline-points.json"), out.resolve("t"), null, "svg");
            assertTrue(r.ok(), r.error());
            Path svg = Path.of(r.raw().getJSONArray("files").getJSONObject(0).getStr("path"));
            String body = Files.readString(svg, StandardCharsets.UTF_8);
            assertTrue(body.contains("担保纠纷案件事实经过时间轴"), "标题中文应原样在 SVG 里");
            assertTrue(body.contains("乙停业失联"), "事件正文中文应原样在 SVG 里");
        } finally {
            deleteTree(out);
        }
    }

    @Test
    @DisplayName("草稿闸：未确认的地图强制写成 *-draft，包装层不许抹平")
    void unconfirmedMapIsForcedToDraft() throws Exception {
        requireRuntime();
        Path out = Files.createTempDirectory("litviz-test-");
        try {
            String src = Files.readString(examples.resolve("timeline-points.json"), StandardCharsets.UTF_8);
            JSONObject m = cn.hutool.json.JSONUtil.parseObj(src);
            m.set("checkpoint", cn.hutool.json.JSONUtil.createObj().set("confirmed", false));
            Path map = out.resolve("unconfirmed.json");
            Files.writeString(map, m.toString(), StandardCharsets.UTF_8);

            LitigationVisualService.Result r = svc.render(map, out.resolve("图"), null, "svg");
            assertTrue(r.ok(), r.error());
            assertTrue(r.raw().getBool("draft", false), "未确认应判为草稿");
            assertTrue(r.raw().getStr("basename").endsWith("-draft"), "文件名应带 -draft");
        } finally {
            deleteTree(out);
        }
    }

    @Test
    @DisplayName("草稿后缀会叠加：前缀本身带 -draft 时引擎再加一次变成 -draft-draft")
    void draftSuffixCompoundsOnAnAlreadyDraftBase() throws Exception {
        // 这是「换风格」那条路的地基：面板从 <名>.map.json 反推 basename，
        // 草稿图拿到的名字本身就带 -draft，直接当前缀传给引擎会得到 -draft-draft，
        // 产物名与既有文件全对不上、五个文件被重复登记一遍。
        // LitigationVisualPanelService.restyle 因此先剥掉 -draft 再渲染。
        // 这条测试钉住引擎的这个行为——上游哪天改了，剥离逻辑就该跟着改，别让它静默失配。
        requireRuntime();
        Path out = Files.createTempDirectory("litviz-test-");
        try {
            String src = Files.readString(examples.resolve("timeline-points.json"), StandardCharsets.UTF_8);
            JSONObject m = cn.hutool.json.JSONUtil.parseObj(src);
            m.set("checkpoint", cn.hutool.json.JSONUtil.createObj().set("confirmed", false));
            Path map = out.resolve("m.json");
            Files.writeString(map, m.toString(), StandardCharsets.UTF_8);

            assertEquals("图-draft",
                    svc.render(map, out.resolve("图"), null, "svg").raw().getStr("basename"),
                    "干净前缀应得到单个 -draft");
            assertEquals("图-draft-draft",
                    svc.render(map, out.resolve("图-draft"), null, "svg").raw().getStr("basename"),
                    "前缀已带 -draft 时引擎会再加一次——restyle 必须先剥掉");
        } finally {
            deleteTree(out);
        }
    }

    @Test
    @DisplayName("三种视觉模式都认，未知模式明确报错而不是默默按默认画")
    void honoursVisualModes() throws Exception {
        requireRuntime();
        Path out = Files.createTempDirectory("litviz-test-");
        try {
            Path map = examples.resolve("timeline-points.json");
            assertEquals("歸藏风", svc.render(map, out.resolve("a"), "歸藏风", "svg").raw().getStr("mode"));
            assertEquals("白描", svc.render(map, out.resolve("b"), "白描", "svg").raw().getStr("mode"));
            assertEquals("奇川风", svc.render(map, out.resolve("c"), null, "svg").raw().getStr("mode"));

            LitigationVisualService.Result bad = svc.render(map, out.resolve("d"), "赛博朋克", "svg");
            assertFalse(bad.ok(), "未知模式应失败");
            assertTrue(bad.error().contains("视觉模式"), "错误里应点名是模式的问题：" + bad.error());
        } finally {
            deleteTree(out);
        }
    }

    @Test
    @DisplayName("坏地图返回结构化错误，不是 traceback")
    void malformedMapFailsCleanly() throws Exception {
        requireRuntime();
        Path out = Files.createTempDirectory("litviz-test-");
        try {
            Path map = out.resolve("bad.json");
            Files.writeString(map, "{\"layout\":\"nope\"}", StandardCharsets.UTF_8);
            LitigationVisualService.Result r = svc.validate(map);
            assertFalse(r.ok());
            assertTrue(r.error().contains("layout"), "错误应点名 layout：" + r.error());
        } finally {
            deleteTree(out);
        }
    }

    // ==== checkpoint ====

    @Test
    @DisplayName("三问由脚本确定性生成，候选清单来自地图里的真实元素")
    void checkpointQuestionsAreDeterministic() {
        requireRuntime();
        LitigationVisualService.Result r =
                svc.checkpoint(examples.resolve("timeline-points.json"), "3");
        assertTrue(r.ok(), r.error());
        String q = r.raw().getStr("questions", "");
        assertTrue(q.contains("① 结构"), "缺结构问");
        assertTrue(q.contains("② 风格"), "缺风格问");
        assertTrue(q.contains("③ 重点"), "缺重点问");
        assertTrue(q.contains("乙停业失联"), "候选清单应是这张图里的真实元素");
    }

    // ==== 参考文档读取 ====

    @Test
    @DisplayName("参考文档能读到")
    void readsReferenceDocs() {
        requireRuntime();
        String s = svc.readReference("references/STANDARDS.md");
        assertNotNull(s, "STANDARDS.md 应可读");
        assertFalse(s.isBlank());
    }

    @Test
    @DisplayName("参考文档路径穿越被挡——参数来自 LLM，不挡就是任意文件读取")
    void blocksPathTraversalInReferences() {
        requireRuntime();
        assertNull(svc.readReference("../../../../etc/passwd"), "穿越到系统文件应被挡");
        assertNull(svc.readReference("../cli.py"), "穿越出 engine/ 应被挡");
        assertNull(svc.readReference("/etc/passwd"), "绝对路径应被挡");
        assertNull(svc.readReference("references/../../PATCHES.md"), "绕回上层应被挡");
    }

    // ==== doctor ====

    @Test
    @DisplayName("doctor 如实回报 graphviz 有无——它只影响流程图，不该被说成整体不可用")
    void doctorReportsGraphvizHonestly() {
        requireRuntime();
        LitigationVisualService.Result r = svc.doctor();
        assertTrue(r.ok(), r.error());
        assertTrue(r.raw().containsKey("graphviz"), "应回报 graphviz 有无");
        assertFalse(r.raw().getStr("python", "").isBlank(), "应回报解释器版本");
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
