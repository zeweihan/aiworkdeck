package com.checkba.service.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 诉讼可视化引擎（litviz）的进程边界。
 *
 * <p>只做三件事：找到能用的 Python、找到 litviz 目录、把 {@code litviz/cli.py} 跑起来
 * 并把它那一行 JSON 解出来。业务语义（产物往哪个项目文件夹落、怎么注册进文件树）
 * 在 {@code LitigationVisualTools} 里，不在这。
 *
 * <p><b>为什么不用 run_python：</b>那个工具是 {@code docker run python:3.9-slim}。
 * 桌面端用户机器上没有 Docker；容器里也没有引擎脚本；它的工作目录跑完即删，
 * 产物落不进项目。出图必须是一条确定性的本地链路。
 *
 * <p><b>解释器版本下限是 3.11</b>，与打包运行时一致（见
 * {@code desktop/scripts/prepare-python-service.js} 的 PY_VERSION）。引擎原本要 3.12+
 * （PEP 701 的 f-string 写法），已由 litviz/PATCHES.md 的 PATCH 2 抹平。
 */
@Service
@Slf4j
public class LitigationVisualService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LitigationVisualService.class);

    /** 单次出图的墙钟上限。引擎是纯计算，正常在秒级；超时基本意味着解释器卡死。 */
    private static final long TIMEOUT_MS = 90_000L;

    /** 引擎最低可用的 Python。低于此版本 import 期就会 SyntaxError，不如提前说清楚。 */
    private static final int MIN_MINOR = 11;

    /** 承载 litviz / graphviz / drawio 的原生资源包 id（docs/NATIVE_PACK_DISTRIBUTION.md） */
    public static final String PACK_ID = "litigation-visual";

    /**
     * 原生资源包服务。**可选注入**：单测与评测直接 {@code new LitigationVisualService()}，
     * 那些场景下资源解析链只走「显式配置 → 随包内置 → dev 目录爬升」三步。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.service.pack.NativePackService packService;

    @Value("${litviz.dir:}")
    private String configuredDir;

    @Value("${litviz.python:}")
    private String configuredPython;

    @Value("${litviz.graphviz-dir:}")
    private String configuredGraphvizDir;

    /** 解析结果只算一次：解释器探测要起子进程，每次工具调用都探一遍是浪费。 */
    private volatile Runtime resolved;

    public record Runtime(Path litvizDir, String python, String pythonVersion, String graphvizDir) {}

    /** 引擎跑完的结构化结果。{@code raw} 是 cli.py 那一行 JSON，供工具层取细节字段。 */
    public record Result(boolean ok, JSONObject raw, String stderr) {
        public String error() {
            return raw != null ? raw.getStr("error", "") : "";
        }
    }

    // ==================== 运行时定位 ====================

    public Runtime runtime() {
        Runtime r = resolved;
        if (r == null) {
            synchronized (this) {
                if (resolved == null) {
                    resolved = resolveRuntime();
                }
                r = resolved;
            }
        }
        return r;
    }

    /** 环境变了（比如用户装了 Python）之后重新探测；测试与 /doctor 用。 */
    public void invalidate() {
        resolved = null;
    }

    /**
     * 向 pack 服务登记「随包内置资源在场」探针，并登记「pack 状态变化」回调。
     *
     * <p>探针供广场的 packReady 与自动补下载区分「资源真缺」与「老版本随包资源还在」；
     * 状态变化回调解决另一个问题——{@link #resolved} 是懒加载且只算一次的缓存，用户在
     * 广场点「安装/卸载」是不重启后端的 live 操作，此前没有任何生产调用点会碰
     * {@link #invalidate()}，装完 pack 面板仍然显示上一次探测出的「不可用」，
     * 只能重启后端才会生效。
     */
    @jakarta.annotation.PostConstruct
    void registerPackProbe() {
        if (packService != null) {
            packService.registerBuiltinProbe(PACK_ID, this::isEngineAvailableWithoutPack);
            packService.onPackChanged(PACK_ID, this::invalidate);
        }
    }

    /**
     * 不借助资源包时引擎是否可用（显式配置 / 随包内置 / dev 目录爬升三步）。
     * 供 packReady 判定复用——随包内置优先于 pack，老用户不该被逼着重下一遍。
     */
    public boolean isEngineAvailableWithoutPack() {
        return resolveLitvizDir(false) != null;
    }

    private Runtime resolveRuntime() {
        Path dir = resolveLitvizDir(true);
        String python = resolvePython();
        String version = python == null ? "" : probeVersion(python);
        String gv = firstNonBlank(configuredGraphvizDir, System.getenv("LITVIZ_GRAPHVIZ_DIR"));
        if (gv == null && dir != null) {
            // 打包态的约定位置：litviz 目录旁边的 graphviz/bin
            Path sibling = dir.resolveSibling("graphviz").resolve("bin");
            if (Files.isDirectory(sibling)) gv = sibling.toString();
        }
        if (gv == null && packService != null) {
            // 末位：资源包里的 graphviz/bin
            Path packBin = packService.componentDir(PACK_ID, "graphviz")
                    .map(p -> p.resolve("bin")).filter(Files::isDirectory).orElse(null);
            if (packBin != null) gv = packBin.toString();
        }
        log.info("litviz runtime: dir={} python={} ({}) graphviz={}", dir, python, version, gv);
        return new Runtime(dir, python, version, gv);
    }

    /**
     * litviz 目录的定位顺序：显式配置 → 宿主注入的环境变量 → 相对后端工作目录往上找。
     *
     * <p>最后一条覆盖两种真实布局：dev 态后端 cwd 是 {@code backend/}，litviz 在
     * {@code ../litviz}；打包态 cwd 是用户数据目录，靠 Electron 注入 LITVIZ_DIR，
     * 走不到这一条。**末位是原生资源包**（规范 §5 的优先级：随包内置优先于 pack）。
     *
     * @param includePack false = 跳过资源包这一步（isEngineAvailableWithoutPack 用）
     */
    private Path resolveLitvizDir(boolean includePack) {
        for (String candidate : new String[]{configuredDir, System.getenv("LITVIZ_DIR")}) {
            if (candidate != null && !candidate.isBlank()) {
                Path p = Paths.get(candidate).toAbsolutePath().normalize();
                if (Files.isRegularFile(p.resolve("cli.py"))) return p;
                log.warn("litviz.dir 指向的位置没有 cli.py，忽略：{}", p);
            }
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        for (Path base : new Path[]{cwd, cwd.getParent(), cwd.getParent() == null ? null : cwd.getParent().getParent()}) {
            if (base == null) continue;
            Path p = base.resolve("litviz");
            if (Files.isRegularFile(p.resolve("cli.py"))) return p.normalize();
        }
        if (includePack && packService != null) {
            Path packDir = packService.componentDir(PACK_ID, "litviz").orElse(null);
            if (packDir != null && Files.isRegularFile(packDir.resolve("cli.py"))) return packDir;
        }
        return null;
    }

    /**
     * 解释器定位：显式配置 → 宿主注入 → 打包运行时的约定位置 → PATH 上的 python3/python。
     *
     * <p>只接受版本达标的候选。不达标的**跳过而不是直接失败**：用户机器上完全可能
     * 同时有一个老 python3 和一个新的，挑错了会得到一句莫名其妙的 SyntaxError。
     */
    private String resolvePython() {
        List<String> candidates = new ArrayList<>();
        addIfPresent(candidates, configuredPython);
        addIfPresent(candidates, System.getenv("LITVIZ_PYTHON"));
        String bundled = System.getenv("AWD_PYTHON_HOME");
        if (bundled != null && !bundled.isBlank()) {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            candidates.add(win ? Paths.get(bundled, "python.exe").toString()
                               : Paths.get(bundled, "bin", "python3.11").toString());
            candidates.add(win ? Paths.get(bundled, "python.exe").toString()
                               : Paths.get(bundled, "bin", "python3").toString());
        }
        candidates.add("python3");
        candidates.add("python");

        String tooOld = null;
        for (String c : candidates) {
            String v = probeVersion(c);
            if (v.isEmpty()) continue;
            if (versionOk(v)) return c;
            tooOld = c + " (" + v + ")";
        }
        if (tooOld != null) {
            log.warn("找到 Python 但版本过低，需要 ≥3.{}：{}", MIN_MINOR, tooOld);
        }
        return null;
    }

    private static void addIfPresent(List<String> out, String v) {
        if (v != null && !v.isBlank()) out.add(v.trim());
    }

    private static boolean versionOk(String version) {
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 3 || (major == 3 && minor >= MIN_MINOR);
        } catch (Exception e) {
            return false;
        }
    }

    private String probeVersion(String python) {
        try {
            Process p = new ProcessBuilder(python, "-c",
                    "import sys;print('%d.%d.%d'%sys.version_info[:3])")
                    .redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return p.exitValue() == 0 && out != null ? out.trim() : "";
        } catch (Exception e) {
            return "";     // 该候选不存在/不可执行，换下一个
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** 环境不具备时的人话说明；具备则返回 null。 */
    public String unavailableReason() {
        Runtime r = runtime();
        if (r.litvizDir() == null) {
            return "找不到诉讼可视化引擎目录（litviz/）。开发态应在仓库根的 litviz/，"
                    + "打包态由桌面端注入 LITVIZ_DIR。可用 litviz.dir 配置显式指定。";
        }
        if (r.python() == null) {
            return "没有找到可用的 Python（需要 3." + MIN_MINOR + " 及以上）。"
                    + "桌面端随包自带，独立部署时请安装 Python 或用 litviz.python 指定路径。";
        }
        return null;
    }

    // ==================== 调用 ====================

    public Result render(Path mapFile, Path outBase, String mode, String formats) {
        List<String> args = new ArrayList<>(List.of(
                "render", "--map", mapFile.toString(), "--out", outBase.toString()));
        if (mode != null && !mode.isBlank()) { args.add("--mode"); args.add(mode.trim()); }
        if (formats != null && !formats.isBlank()) { args.add("--formats"); args.add(formats.trim()); }
        return invoke(args);
    }

    public Result checkpoint(Path mapFile, String suggest) {
        List<String> args = new ArrayList<>(List.of("checkpoint", "--map", mapFile.toString()));
        if (suggest != null && !suggest.isBlank()) { args.add("--suggest"); args.add(suggest.trim()); }
        return invoke(args);
    }

    public Result validate(Path mapFile) {
        return invoke(List.of("validate", "--map", mapFile.toString()));
    }

    public Result doctor() {
        return invoke(List.of("doctor"));
    }

    /**
     * 驱动时间轴大师（mqc-timeline-master）分段管线的一个阶段。
     *
     * <p>管线以 workdir 为状态目录（state.json 与模型产出的四份 JSON 都在里面），
     * cli.py 的 timeline 子命令负责 chdir 与文本→JSON 的转达。模型要写的文件由
     * {@code LitigationTimelineTools} 直接写进 workdir，不经过这里。
     *
     * @param emphasisSource 仅 mark 阶段有意义：user/model/none，如实记录深红是谁挑的
     */
    public Result timeline(Path workdir, String stage, List<String> stageArgs, String emphasisSource) {
        List<String> args = new ArrayList<>(List.of(
                "timeline", "--workdir", workdir.toString(), "--stage", stage));
        if (emphasisSource != null && !emphasisSource.isBlank()) {
            args.add("--emphasis-source");
            args.add(emphasisSource.trim());
        }
        if (stageArgs != null) {
            for (String a : stageArgs) {
                if (a != null && !a.isBlank()) args.add(a);
            }
        }
        return invoke(args);
    }

    private Result invoke(List<String> cliArgs) {
        Runtime rt = runtime();
        String why = unavailableReason();
        if (why != null) {
            return new Result(false, JSONUtil.createObj().set("ok", false).set("error", why), "");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(rt.python());
        cmd.add(rt.litvizDir().resolve("cli.py").toString());
        cmd.addAll(cliArgs);

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(rt.litvizDir().toFile());
            // 子进程的中文输出必须按 UTF-8 编码，否则 Windows 上会按 GBK 出来，
            // 图名/事件文本一路乱码进 JSON。
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            if (rt.graphvizDir() != null) {
                pb.environment().put("LITVIZ_GRAPHVIZ_DIR", rt.graphvizDir());
            }
            proc = pb.start();

            // stdout/stderr 必须边跑边排空。子进程输出超过管道缓冲（~64KB）就会阻塞在
            // write，等进程退出再读的话它永远不会退出——PythonTools 踩过同一个坑。
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread op = pump(proc.getInputStream(), out);
            Thread ep = pump(proc.getErrorStream(), err);

            if (!proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                // 超时这一刻排空线程可能还卡在 synchronized(sink) 里追加最后一行——
                // 读必须跟它用同一把锁，否则 toString() 可能读到半写内容，
                // 极端情况下内部数组扩容中途还可能抛异常，被外层 catch 吞掉后
                // 把「出图超时」这个清楚的提示换成一个看起来像引擎崩溃的困惑消息。
                return new Result(false, JSONUtil.createObj().set("ok", false)
                        .set("error", "出图超时（" + TIMEOUT_MS / 1000 + " 秒）"), readSink(err));
            }
            op.join(5000);
            ep.join(5000);

            String stdout = readSink(out);
            String stderr = readSink(err);

            if (stdout.isEmpty()) {
                // cli.py 的契约是「无论成败都打一行 JSON」。什么都没有，说明解释器
                // 自己崩了（缺模块、权限、被杀），stderr 才是有用的那半边。
                return new Result(false, JSONUtil.createObj().set("ok", false)
                        .set("error", "引擎没有返回结果（退出码 " + proc.exitValue() + "）"), stderr);
            }
            // 契约是恰好一行；真出现多行时取最后一行（JSON 一定是最后打的），
            // 不直接失败——宁可少一点洁癖，也别让用户为一行杂音丢掉整张图。
            String lastLine = stdout.substring(stdout.lastIndexOf('\n') + 1).trim();
            JSONObject json = JSONUtil.parseObj(lastLine);
            return new Result(json.getBool("ok", false), json, stderr);

        } catch (Exception e) {
            log.error("litviz 调用失败: {}", cmd, e);
            return new Result(false, JSONUtil.createObj().set("ok", false)
                    .set("error", e.getClass().getSimpleName() + ": " + e.getMessage()), "");
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }

    private Thread pump(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) { sink.append(line).append('\n'); }
                }
            } catch (Exception ignored) {
                // 进程结束/流关闭属正常退出
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 安全地取出 pump() 排空线程正在写的缓冲区快照。StringBuilder 本身不是线程安全的，
     * 必须跟 pump() 里 append 用的同一把锁（sink 自身的对象监视器）同步，
     * 否则 toString() 可能在 append 中途读到半写内容，甚至在内部数组扩容中途抛异常。
     * 包可见：供测试直接驱动，不依赖真实子进程。
     */
    static String readSink(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString().trim();
        }
    }

    /** 读一份引擎自带的参考文档（渐进披露用）。越界返回 null。 */
    public String readReference(String relativePath) {
        Runtime rt = runtime();
        if (rt.litvizDir() == null) return null;
        Path engine = rt.litvizDir().resolve("skills").resolve("mqc-litigation-visual-redraw").normalize();
        Path target = engine.resolve(relativePath).normalize();
        // 只许读 engine/ 内部：relativePath 来自 LLM，不做这一步就是任意文件读取。
        if (!target.startsWith(engine) || !Files.isRegularFile(target)) return null;
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取引擎参考文档失败: {}", target, e);
            return null;
        }
    }
}
