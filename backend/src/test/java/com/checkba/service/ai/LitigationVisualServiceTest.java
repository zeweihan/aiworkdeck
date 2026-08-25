package com.checkba.service.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.checkba.service.pack.NativePackService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
            examples = rt.litvizDir().resolve("mqc-litigation-visual-redraw").resolve("examples");
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

    /**
     * 修复：resolved 此前只算一次且全仓没有任何生产调用点会碰 invalidate()——用户在广场
     * 装完 litigation-visual 资源包（live 安装，不重启后端）后，runtime() 会一直返回
     * 装包前缓存的旧结果。
     *
     * <p>不依赖"litviz 目录一开始必须完全解析不到"（cwd 相对路径的兜底可能会在这台机器上
     * 真的找到仓库里的 litviz/，环境不同结果不同）：只断言"configuredDir 刚落盘 cli.py 后，
     * 不失效缓存则 runtime() 还是原来那个缓存实例；失效后才会重新解析并优先命中 configuredDir"
     * ——这条钉住的是缓存本身会不会刷新，与这台机器上到底有没有真实 litviz/ 无关。
     */
    @Test
    @DisplayName("修复：invalidate() 后重新探测，pack 装完不必重启后端就能生效")
    void invalidateForcesReResolutionAfterCliPyAppears(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        LitigationVisualService fresh = new LitigationVisualService();
        ReflectionTestUtils.setField(fresh, "configuredDir", tempDir.toString());
        ReflectionTestUtils.setField(fresh, "configuredPython", "");
        ReflectionTestUtils.setField(fresh, "configuredGraphvizDir", "");

        Path tempDirNormalized = tempDir.toAbsolutePath().normalize();
        // tempDir 里还没有 cli.py：resolveLitvizDir 跳过它，缓存下当时能解析到的结果
        LitigationVisualService.Runtime before = fresh.runtime();
        assertFalse(tempDirNormalized.equals(before.litvizDir()), "cli.py 还没落盘，不应解析到 tempDir");

        // 模拟"用户在广场装完 litigation-visual 资源包"：cli.py 落盘到 configuredDir
        Files.writeString(tempDir.resolve("cli.py"), "# fake cli\n", StandardCharsets.UTF_8);

        // 不失效缓存的话，runtime() 应仍返回失效前缓存的同一个实例——钉住修复前的故障现象
        assertSame(before, fresh.runtime(), "不失效缓存时应仍返回同一个缓存实例（钉住修复前的行为）");

        fresh.invalidate();

        LitigationVisualService.Runtime after = fresh.runtime();
        assertEquals(tempDirNormalized, after.litvizDir(),
                "失效后应重新解析；configuredDir 优先级最高，应命中刚落盘的 cli.py");
    }

    /**
     * 上一条测的是"invalidate() 本身管不管用"——这条测的是修复真正加的那一行：
     * registerPackProbe 有没有把 invalidate 注册给 packService，pack 状态变化时
     * 是不是真的会调用到它。两条缺一都不能证明生产环境里这条链路是通的。
     */
    @Test
    @DisplayName("修复：registerPackProbe 把 invalidate 登记进 packService.onPackChanged，回调触发时真的会重新解析")
    void registerPackProbeWiresInvalidateToPackService(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        LitigationVisualService fresh = new LitigationVisualService();
        ReflectionTestUtils.setField(fresh, "configuredDir", tempDir.toString());
        ReflectionTestUtils.setField(fresh, "configuredPython", "");
        ReflectionTestUtils.setField(fresh, "configuredGraphvizDir", "");

        NativePackService packService = mock(NativePackService.class);
        ReflectionTestUtils.setField(fresh, "packService", packService);

        fresh.registerPackProbe(); // 模拟 Spring 的 @PostConstruct

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(packService).onPackChanged(eq(LitigationVisualService.PACK_ID), captor.capture());

        Path tempDirNormalized = tempDir.toAbsolutePath().normalize();
        LitigationVisualService.Runtime before = fresh.runtime();
        assertFalse(tempDirNormalized.equals(before.litvizDir()));

        Files.writeString(tempDir.resolve("cli.py"), "# fake cli\n", StandardCharsets.UTF_8);
        assertSame(before, fresh.runtime(), "登记的回调触发前不该重新解析");

        // 模拟 NativePackService 在 install()/uninstall()/syncRevoked() 里调用注册的回调
        captor.getValue().run();

        assertEquals(tempDirNormalized, fresh.runtime().litvizDir(),
                "packService 触发注册的回调后应该重新解析并命中刚落盘的 cli.py");
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

    // ==== 并发安全 ====

    /**
     * 修复：超时分支读 stderr 用的是裸 {@code err.toString()}，没有跟排空线程写
     * 用的同一把锁（{@code synchronized(sink)}）同步——StringBuilder 本身不是
     * 线程安全的，toString() 与 append() 并发时可能读到半写的内容，极端情况下
     * 还可能因为内部数组扩容中途而抛异常，被外层 catch 吞掉后把「出图超时」这个
     * 清楚的提示换成一个看起来像引擎崩溃的困惑消息。
     *
     * <p>不依赖自然产生的竞态窗口（那样测试会时红时绿）：用两个 CountDownLatch
     * 把交错顺序摆死——写线程先拿到锁、追加一半内容后卡住不放锁，主线程这时去读。
     * 若读跟写线程用的是同一把锁，读必须被挡住，直到写线程把剩下内容追加完、
     * 释放锁之后，读到的才是完整值；若读没有同步，会立刻读到「半写」的内容。
     */
    @Test
    @DisplayName("修复：超时分支读 stderr 必须与排空线程的写用同一把锁，不能读到半写的内容")
    void timeoutPathReadsStderrUnderTheSameLockTheWriterUses() throws Exception {
        StringBuilder sink = new StringBuilder();
        CountDownLatch writerHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (sink) {
                sink.append("partial");
                writerHoldingLock.countDown();
                try {
                    // 最多卡 1 秒——即便主线程的读没有被挡住（说明有 bug），测试也不会真的卡死。
                    releaseWriter.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                sink.append("-done");
            }
        });
        writer.start();
        assertTrue(writerHoldingLock.await(2, TimeUnit.SECONDS), "写线程应先拿到锁并追加了一半内容");

        long startNanos = System.nanoTime();
        String observed = LitigationVisualService.readSink(sink);
        long blockedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        writer.join(2000);

        assertEquals("partial-done", observed,
                "读必须等写线程把完整内容追加完才能拿到——读到 \"" + observed + "\" 说明没有跟写用同一把锁同步");
        assertTrue(blockedMillis >= 800,
                "同步读应该被写线程持锁的那段时间（约 1 秒）挡住，实际只等了 " + blockedMillis + "ms");
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
