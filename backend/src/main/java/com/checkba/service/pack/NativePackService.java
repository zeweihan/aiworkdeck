package com.checkba.service.pack;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.service.LangText;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 原生资源包（native pack）的安装器。规范：docs/NATIVE_PACK_DISTRIBUTION.md。
 *
 * <p>pack = 「被宿主代码按路径消费的重资源」（Python 引擎、平台原生二进制、静态资产），
 * 不进 JVM。它的信任等级与 JAR 插件相同（包里有会被解释执行/spawn 的内容），
 * 因此**沿用插件 registry 的同一对 Ed25519 密钥**验签，公钥未配置 = 拒绝一切安装。
 *
 * <p>与 {@code PluginMarketService} 的一个刻意差异：pack 的 manifest 是托管的静态文件，
 * 签名直接盖在 <b>manifest 原始字节</b>上（旁挂 {@code manifest.json.sig}），
 * 不走 canonical JSON 重建——从根上避开跨语言键序坑。
 *
 * <p>落盘布局（{@code ai.packs.dir} 之下）：
 * <pre>
 * .staging/&lt;id&gt;-&lt;version&gt;/     安装事务工作区（*.part 断点文件 + unpack/）
 * &lt;id&gt;/current.json             原子指针 {version, activatedAt, revoked}
 * &lt;id&gt;/&lt;version&gt;/.pack-complete 逐文件复核通过后才写的完成标记
 * &lt;id&gt;/&lt;version&gt;/&lt;unpackDir&gt;/  各组件
 * </pre>
 * 资源解析<b>只认</b>「current.json 指向且带 .pack-complete」的目录，半成品对外不可见。
 */
@Service
@Slf4j
public class NativePackService {

    /** 与 skill / 插件 id 同一套规则，兼防路径穿越 */
    private static final Pattern PACK_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    /** 宿主认识的资源↔宿主机器契约版本；manifest 声明别的值即拒装 */
    private static final int SUPPORTED_ENGINE_API = 1;

    private static final int SUPPORTED_SCHEMA = 1;

    /** 单个组件的下载重试次数，每次换下一个源 */
    private static final int MAX_ATTEMPTS = 3;

    /** 解压防护：单个压缩包的条目数与解压后总体积上限 */
    private static final int MAX_ENTRIES = 5000;
    private static final long MAX_UNPACKED_BYTES = 500L * 1024 * 1024;

    /** manifest 查询缓存时长（/api/packs/{id}/info） */
    private static final long MANIFEST_TTL_MS = 5 * 60 * 1000L;

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    private static final String COMPLETE_MARKER = ".pack-complete";
    private static final String CURRENT_JSON = "current.json";
    private static final String CONTENTS_LIST = "contents.sha256";

    // 状态机（规范 §4.3；state 值直接进 API 响应）
    public static final String STATE_NOT_INSTALLED = "not_installed";
    public static final String STATE_DOWNLOADING = "downloading";
    public static final String STATE_VERIFYING = "verifying";
    public static final String STATE_INSTALLING = "installing";
    public static final String STATE_READY = "ready";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_REVOKED = "revoked";

    private final PackProperties props;
    private final String publicKeyPem;
    private final String appVersion;

    /** 安装串行执行：同一 pack 去重，不同 pack 也排队（下载是 IO 密集，并发下没有收益） */
    private final ExecutorService installer =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "native-pack-installer");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, PackStatus> statuses = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedManifest> manifestCache = new ConcurrentHashMap<>();

    /**
     * 「随包内置资源在场」探针：packId -> 判定函数，由资源消费方（如
     * {@link com.checkba.service.ai.LitigationVisualService}）自行登记。
     *
     * <p>存在的理由：老版本用户的随包资源仍在，不该被逼着重下一遍 pack
     * （规范 §5 的解析优先级「随包优先于 pack」）。
     */
    private final Map<String, BooleanSupplier> builtinProbes = new ConcurrentHashMap<>();

    private volatile HttpClient httpClient;

    public NativePackService(
            PackProperties props,
            @Value("${ai.plugins.registry-public-key:}") String publicKeyPem,
            @Value("${telemetry.app-version:${AWD_APP_VERSION:dev}}") String appVersion) {
        this.props = props;
        this.publicKeyPem = publicKeyPem;
        this.appVersion = appVersion;
    }

    // ==================== 对外数据结构 ====================

    /** manifest 里的一个组件 */
    public record Component(String name, List<String> platforms, String archive,
                            long size, String sha256, String unpackDir, List<String> urls) {}

    /** pack manifest（托管静态文件，签名盖在原始字节上） */
    public record Manifest(int schema, String id, String version, String publishedAt,
                           String minAppVersion, int engineApi, List<Component> components) {}

    /** 封禁表条目 */
    public record RevokedPack(String id, String version, String reason, String revokedAt) {}

    /** {@code /api/packs/{id}/info} 的返回体 */
    public record PackInfo(String latestVersion, long totalSize) {}

    /**
     * 安装进度（内存态；重启后按磁盘重建 ready / not_installed）。
     *
     * <p>字段全部 {@code volatile}：写者是安装用的独立执行器线程（{@code installer}），
     * 读者是任意一条处理 {@code GET /api/packs/{id}/status} 的 Tomcat 请求线程——前端
     * 每秒轮询一次、app-e2e 测试脚本轮询更密。普通字段没有 happens-before 关系，理论上
     * JIT 可以让读线程长期看不到写线程的更新。2026-08-20 排查 app-e2e J13「下载进度
     * 卡在固定字节」时顺带发现这里没上 {@code volatile}——之后查实那次卡死的真根因
     * 其实是 run.mjs 里 J13 段的一处断言 bug（{@code j13WaitText('已启用', ...)} 没
     * 限定容器，被市场列表里另一个默认启用的 skill 的「已启用」标签提前撞上，接着立刻
     * 采的那个 {@code /status} 快照恰好落在下载早期），不是这里；但字段本身缺
     * {@code volatile} 仍是一个真实、独立成立的内存可见性隐患，顺手补上。
     */
    public static class PackStatus {
        private volatile String id;
        private volatile String state = STATE_NOT_INSTALLED;
        private volatile String installedVersion;
        private volatile long bytesDownloaded;
        private volatile long bytesTotal;
        private volatile String error;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
        public String getInstalledVersion() { return installedVersion; }
        public void setInstalledVersion(String installedVersion) { this.installedVersion = installedVersion; }
        public long getBytesDownloaded() { return bytesDownloaded; }
        public void setBytesDownloaded(long bytesDownloaded) { this.bytesDownloaded = bytesDownloaded; }
        public long getBytesTotal() { return bytesTotal; }
        public void setBytesTotal(long bytesTotal) { this.bytesTotal = bytesTotal; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    private record CachedManifest(Manifest manifest, long fetchedAt) {}

    // ==================== 查询 ====================

    /** 装好且未被封禁（current.json 指向的版本目录带完成标记） */
    public boolean isReady(String packId) {
        return currentVersionDir(packId).isPresent();
    }

    /**
     * 资源是否可用 = pack 已就绪 <b>或</b> 随包内置资源在场。
     * 广场的 packReady、自动补下载的判据都用它。
     */
    public boolean resourceReady(String packId) {
        if (isReady(packId)) return true;
        BooleanSupplier probe = builtinProbes.get(packId);
        try {
            return probe != null && probe.getAsBoolean();
        } catch (Exception e) {
            log.debug("Builtin probe for pack {} failed: {}", packId, e.getMessage());
            return false;
        }
    }

    /** 登记「随包内置资源在场」探针（资源消费方在 @PostConstruct 里调） */
    public void registerBuiltinProbe(String packId, BooleanSupplier probe) {
        if (packId != null && probe != null) builtinProbes.put(packId, probe);
    }

    /**
     * 取某个组件的解压目录。未安装 / 已封禁 / 目录不存在都返回 empty，
     * 调用方据此走自己的降级分支。
     */
    public Optional<Path> componentDir(String packId, String unpackDir) {
        if (unpackDir == null || !isSafeSegment(unpackDir)) return Optional.empty();
        return currentVersionDir(packId)
                .map(v -> v.resolve(unpackDir))
                .filter(Files::isDirectory);
    }

    /** 本地已装（含被封禁的）pack id */
    public Set<String> installedPackIds() {
        Path root = packsRoot();
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.isDirectory(root)) return ids;
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve(CURRENT_JSON)))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> PACK_ID.matcher(name).matches())
                    .forEach(ids::add);
        } catch (IOException e) {
            log.warn("列举已装资源包失败: {}", e.getMessage());
        }
        return ids;
    }

    /** 当前状态。内存里没有记录时按磁盘判定（重启后的 ready / revoked / not_installed）。 */
    public PackStatus status(String packId) {
        PackStatus live = statuses.get(packId);
        if (live != null) return live;
        PackStatus st = new PackStatus();
        st.setId(packId);
        JSONObject current = readCurrent(packId);
        if (current != null) {
            st.setInstalledVersion(current.getStr("version"));
            if (current.getBool("revoked", false)) {
                st.setState(STATE_REVOKED);
            } else if (isReady(packId)) {
                st.setState(STATE_READY);
            }
        }
        return st;
    }

    /**
     * 拉一次 manifest 回答「最新版多大」（按本平台过滤后的压缩包字节和）。
     * 命中 5 分钟缓存时不发请求。
     */
    public PackInfo info(String packId) {
        requireValidId(packId);
        Manifest m = cachedManifest(packId);
        long total = 0;
        for (Component c : componentsForPlatform(m)) {
            total += c.size();
        }
        return new PackInfo(m.version(), total);
    }

    // ==================== 安装 ====================

    /**
     * 异步安装（幂等）：同一 pack 已在队列里就直接返回，前端轮询 status 看进度。
     */
    public void installAsync(String packId) {
        requireValidId(packId);
        requireEnabled();
        if (!inFlight.add(packId)) {
            log.info("Pack {} install already in flight, ignoring duplicate request", packId);
            return;
        }
        PackStatus st = liveStatus(packId);
        st.setState(STATE_DOWNLOADING);
        st.setError(null);
        installer.submit(() -> {
            try {
                install(packId);
            } catch (Exception e) {
                log.warn("Pack {} install failed: {}", packId, e.getMessage());
            } finally {
                inFlight.remove(packId);
            }
        });
    }

    /**
     * 同步安装事务（规范 §4.2）：验签 → 版本/engineApi 校验 → 逐组件断点续传下载并比对
     * sha256 → 解压（四重防护）→ 按包内 contents.sha256 逐文件复核 → 原子 rename
     * → 写完成标记 → 切 current.json 指针 → 删旧版本。
     *
     * <p>失败处置：验签/哈希失败删产物；网络中断保留 {@code .part} 供下次续传。
     *
     * @return 安装的版本号
     */
    public synchronized String install(String packId) {
        requireValidId(packId);
        requireEnabled();
        PackStatus st = liveStatus(packId);
        try {
            st.setState(STATE_DOWNLOADING);
            st.setError(null);
            String version = doInstall(packId, st);
            st.setState(STATE_READY);
            st.setInstalledVersion(version);
            return version;
        } catch (RuntimeException e) {
            st.setState(STATE_FAILED);
            st.setError(e.getMessage());
            throw e;
        }
    }

    private String doInstall(String packId, PackStatus st) {
        Manifest m = fetchAndVerifyManifest(packId);
        checkCompatibility(m);

        List<Component> components = componentsForPlatform(m);
        if (components.isEmpty()) {
            throw new IllegalStateException(LangText.of(
                    "资源包 " + packId + " 没有适用于当前平台（" + platform() + "）的组件",
                    "Pack " + packId + " has no component for this platform (" + platform() + ")"));
        }

        Path versionDir = packsRoot().resolve(packId).resolve(m.version());
        if (Files.isRegularFile(versionDir.resolve(COMPLETE_MARKER))) {
            // 幂等重装：目标版本已完整落盘，只重写指针（不下载任何字节）
            writeCurrent(packId, m.version(), false);
            pruneOtherVersions(packId, m.version());
            st.setBytesTotal(totalSize(components));
            st.setBytesDownloaded(totalSize(components));
            log.info("Pack {} v{} already installed, pointer refreshed", packId, m.version());
            return m.version();
        }

        Path staging = packsRoot().resolve(".staging").resolve(packId + "-" + m.version());
        Path unpack = staging.resolve("unpack");
        try {
            Files.createDirectories(staging);
            // .part 断点文件留着续传，半成品解压目录每次重来
            FileUtil.del(unpack.toFile());
            Files.createDirectories(unpack);
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("创建安装工作区失败: ", "Failed to create staging area: ") + e.getMessage());
        }

        st.setBytesTotal(totalSize(components));
        long completed = 0;
        List<Path> archives = new ArrayList<>();
        for (Component c : components) {
            Path part = staging.resolve(c.archive() + ".part");
            downloadComponent(packId, m.version(), c, part, st, completed);
            completed += c.size() > 0 ? c.size() : sizeOf(part);
            st.setBytesDownloaded(completed);
            archives.add(part);
        }

        st.setState(STATE_INSTALLING);
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            Path target = unpack.resolve(c.unpackDir());
            extract(archives.get(i), target);
            verifyContents(target);
        }

        Path versionParent = versionDir.getParent();
        try {
            Files.createDirectories(versionParent);
            FileUtil.del(versionDir.toFile());
            try {
                Files.move(unpack, versionDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 跨文件系统时原子移动不可用，退化为拷贝（staging 与目标默认同盘，这条极少走到）
                FileUtil.copyContent(unpack.toFile(), versionDir.toFile(), true);
                FileUtil.del(unpack.toFile());
            }
            Files.writeString(versionDir.resolve(COMPLETE_MARKER), m.version(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FileUtil.del(versionDir.toFile());
            throw new IllegalStateException(LangText.of("安装落盘失败: ", "Failed to finalize installation: ") + e.getMessage());
        }

        writeCurrent(packId, m.version(), false);
        pruneOtherVersions(packId, m.version());
        FileUtil.del(staging.toFile());
        log.info("Installed native pack {} v{} ({} component(s))", packId, m.version(), components.size());
        return m.version();
    }

    /** 卸载：删 packs/&lt;id&gt;/ 整目录（含 current.json）。canonical 守卫只许删正下方。 */
    public synchronized void uninstall(String packId) {
        requireValidId(packId);
        Path root = packsRoot();
        Path dir = root.resolve(packId);
        try {
            String canonicalRoot = root.toFile().getCanonicalPath();
            String canonicalDir = dir.toFile().getCanonicalPath();
            if (!canonicalDir.startsWith(canonicalRoot + java.io.File.separator)) {
                throw new IllegalArgumentException(LangText.of("非法资源包目录: ", "Invalid pack directory: ") + packId);
            }
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("路径检查失败: ", "Path check failed: ") + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            // 从未装成功：pack 目录不存在，但可能有失败安装留下的 staging 残留（.part 等）。
            // 卸载本该是幂等的收口动作——不能因为"没装成功"就拒绝清理，把用户卡在
            // 「装不上、卸不掉」的死循环里（见 416 断点续传死循环的排查记录）。
            deleteStaging(packId);
            statuses.remove(packId);
            manifestCache.remove(packId);
            log.info("Pack {} was never installed; cleared staging leftovers only", packId);
            return;
        }
        FileUtil.del(dir.toFile());
        deleteStaging(packId);
        statuses.remove(packId);
        manifestCache.remove(packId);
        log.info("Uninstalled native pack {}", packId);
    }

    // ==================== 下载 ====================

    /** 4xx（含越界 Range → 416）：不是网络抖动，不该保留 .part 等着续传 */
    private static final class NonResumableHttpException extends IOException {
        NonResumableHttpException(int status) {
            super("HTTP " + status);
        }
    }

    private void downloadComponent(String packId, String version, Component c,
                                   Path part, PackStatus st, long baseBytes) {
        List<String> sources = sourceUrls(packId, version, c);
        if (sources.isEmpty()) {
            throw new IllegalStateException(LangText.of("没有可用的下载源", "No download source configured"));
        }
        String lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String url = sources.get(attempt % sources.size());
            try {
                // 构造性防御：本地残留 .part 已经不小于 manifest 声明的组件大小，继续带着
                // 这段 Range 请求必然越界（服务器如实回 416）——直接清空重下，不必先挨一次 416。
                if (c.size() > 0 && sizeOf(part) >= c.size()) {
                    FileUtil.del(part.toFile());
                }
                fetchToFile(url, part, st, baseBytes);
                st.setState(STATE_VERIFYING);
                String actual = sha256Hex(part);
                if (actual.equalsIgnoreCase(c.sha256())) {
                    st.setState(STATE_DOWNLOADING);
                    return;
                }
                // 内容与签名清单不符：产物不可信，删了换下一个源
                log.warn("Pack {} component {} sha256 mismatch from {} (expected {}, got {})",
                        packId, c.name(), url, c.sha256(), actual);
                FileUtil.del(part.toFile());
                lastError = LangText.of("下载内容与签名清单不符", "Downloaded content does not match the signed manifest");
                st.setState(STATE_DOWNLOADING);
            } catch (NonResumableHttpException e) {
                // 4xx（含越界 Range → 416）不是网络抖动：fetchToFile 已经删掉 .part，
                // 下一次尝试（哪怕就在这轮循环里）会发一次不带 Range 的全量请求，
                // 不必等用户再点一次「安装」才能自愈。
                lastError = e.getMessage();
                log.warn("Pack {} component {} download from {} rejected ({}); restarting full download",
                        packId, c.name(), url, e.getMessage());
            } catch (IOException | InterruptedException e) {
                // 网络中断：保留 .part，下次续传
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = e.getMessage();
                log.warn("Pack {} component {} download from {} failed: {}", packId, c.name(), url, e.getMessage());
            }
        }
        throw new IllegalStateException(LangText.of("组件下载失败: ", "Component download failed: ")
                + c.name() + " (" + lastError + ")");
    }

    /**
     * 一次下载尝试，带 HTTP Range 断点续传：本地已有 N 字节就带 {@code Range: bytes=N-}；
     * 服务器不回 206（不支持 Range，或返回了整体 200）则从头重写。
     */
    private void fetchToFile(String url, Path part, PackStatus st, long baseBytes)
            throws IOException, InterruptedException {
        long have = sizeOf(part);
        HttpResponse<InputStream> resp = httpGetRange(url, have);
        int status = resp.statusCode();
        boolean append;
        if (status == 206 && have > 0) {
            append = true;
        } else if (status == 200) {
            append = false;
            have = 0;
        } else {
            resp.body().close();
            if (status >= 400 && status < 500) {
                // 4xx（典型如 416 越界 Range）不是可续传条件：.part 已经跟服务器的认知
                // 对不上了，留着只会让下一次尝试带着同一个必错的 Range 再犯一次同样的错。
                FileUtil.del(part.toFile());
                throw new NonResumableHttpException(status);
            }
            throw new IOException("HTTP " + status);
        }

        Files.createDirectories(part.getParent());
        long written = have;
        try (InputStream in = resp.body();
             OutputStream out = append
                     ? Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                     : Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                st.setBytesDownloaded(baseBytes + written);
            }
        }
    }

    private List<String> sourceUrls(String packId, String version, Component c) {
        List<String> urls = new ArrayList<>();
        for (String base : props.getBaseUrls()) {
            if (base == null || base.isBlank()) continue;
            urls.add(trimSlash(base) + "/" + packId + "/" + version + "/" + c.archive());
        }
        if (c.urls() != null) {
            for (String u : c.urls()) {
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) urls.add(u);
            }
        }
        return urls;
    }

    // ==================== manifest 与验签 ====================

    private Manifest cachedManifest(String packId) {
        CachedManifest cached = manifestCache.get(packId);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < MANIFEST_TTL_MS) {
            return cached.manifest();
        }
        Manifest m = fetchAndVerifyManifest(packId);
        manifestCache.put(packId, new CachedManifest(m, System.currentTimeMillis()));
        return m;
    }

    /**
     * 按序试各下载源拉 {@code manifest.json} + {@code manifest.json.sig} 并验签。
     *
     * <p>取不到就换下一个源；<b>验签失败直接中止</b>（不再试别的源）——签名不符
     * 说明这条分发链路上有人动过手脚，换个镜像继续找是错的方向。
     */
    Manifest fetchAndVerifyManifest(String packId) {
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalStateException(LangText.of(
                    "未配置注册表公钥（ai.plugins.registry-public-key），拒绝安装资源包",
                    "Registry public key not configured (ai.plugins.registry-public-key); pack installation refused"));
        }
        String lastError = null;
        for (String base : props.getBaseUrls()) {
            if (base == null || base.isBlank()) continue;
            String manifestUrl = trimSlash(base) + "/" + packId + "/manifest.json";
            byte[] raw;
            byte[] sig;
            try {
                raw = httpGetBytes(manifestUrl);
                sig = httpGetBytes(manifestUrl + ".sig");
            } catch (Exception e) {
                lastError = e.getMessage();
                log.debug("Pack {} manifest not available at {}: {}", packId, manifestUrl, e.getMessage());
                continue;
            }
            if (!verifySignature(raw, new String(sig, StandardCharsets.UTF_8))) {
                throw new IllegalStateException(LangText.of(
                        "资源包清单签名验证失败，已中止（来源可能被篡改）",
                        "Pack manifest signature verification failed; aborted (the source may have been tampered with)"));
            }
            Manifest m = parseManifest(raw);
            if (!packId.equals(m.id())) {
                throw new IllegalStateException(LangText.of(
                        "清单里的 id 与请求不符: ", "Manifest id does not match the request: ") + m.id());
            }
            return m;
        }
        throw new IllegalStateException(LangText.of("资源包清单不可达: ", "Pack manifest unreachable: ")
                + packId + (lastError == null ? "" : " (" + lastError + ")"));
    }

    boolean verifySignature(byte[] manifestBytes, String signatureB64) {
        try {
            PublicKey key = parsePublicKey(publicKeyPem);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifestBytes);
            return verifier.verify(Base64.getDecoder().decode(signatureB64.trim()));
        } catch (Exception e) {
            log.error("Pack manifest signature verification error: {}", e.getMessage());
            return false;
        }
    }

    static Manifest parseManifest(byte[] raw) {
        JSONObject j;
        try {
            j = JSONUtil.parseObj(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("资源包清单无法解析: ", "Failed to parse pack manifest: ") + e.getMessage());
        }
        List<Component> components = new ArrayList<>();
        JSONArray arr = j.getJSONArray("components");
        if (arr != null) {
            for (Object o : arr) {
                JSONObject c = (JSONObject) o;
                components.add(new Component(
                        c.getStr("name"),
                        strList(c.getJSONArray("platforms")),
                        c.getStr("archive"),
                        c.getLong("size", 0L),
                        c.getStr("sha256"),
                        c.getStr("unpackDir"),
                        strList(c.getJSONArray("urls"))));
            }
        }
        Manifest m = new Manifest(
                j.getInt("schema", 0),
                j.getStr("id"),
                j.getStr("version"),
                j.getStr("publishedAt"),
                j.getStr("minAppVersion"),
                j.getInt("engineApi", 0),
                components);
        if (m.id() == null || m.version() == null || components.isEmpty()) {
            throw new IllegalStateException(LangText.of("资源包清单缺少必需字段", "Pack manifest is missing required fields"));
        }
        for (Component c : components) {
            if (!isSafeSegment(c.archive()) || !isSafeSegment(c.unpackDir()) || c.sha256() == null) {
                throw new IllegalStateException(LangText.of("资源包清单含非法组件: ", "Pack manifest contains an invalid component: ") + c.name());
            }
        }
        return m;
    }

    /** schema / engineApi / minAppVersion 三道兼容闸 */
    private void checkCompatibility(Manifest m) {
        if (m.schema() != SUPPORTED_SCHEMA) {
            throw new IllegalStateException(LangText.of(
                    "资源包清单版本不受支持（schema=" + m.schema() + "），请升级应用",
                    "Unsupported pack manifest schema (" + m.schema() + "); please upgrade the app"));
        }
        if (m.engineApi() != SUPPORTED_ENGINE_API) {
            throw new IllegalStateException(LangText.of(
                    "资源包引擎契约版本不受支持（engineApi=" + m.engineApi() + "），请升级应用",
                    "Unsupported pack engine API (" + m.engineApi() + "); please upgrade the app"));
        }
        String min = m.minAppVersion();
        if (min == null || min.isBlank()) return;
        if (!isSemver(appVersion)) {
            // dev 态 / 未注入 AWD_APP_VERSION：拿不到可比较的版本号就放行，
            // 硬编码一个假版本只会让开发环境按错误的前提做判断
            log.warn("应用版本号 '{}' 无法解析，跳过 minAppVersion({}) 校验", appVersion, min);
            return;
        }
        if (compareSemver(appVersion, min) < 0) {
            throw new IllegalStateException(LangText.of(
                    "资源包需要应用 " + min + " 及以上（当前 " + appVersion + "），请先升级应用",
                    "This pack requires app version " + min + " or newer (current " + appVersion + "); please upgrade first"));
        }
    }

    // ==================== 解压与复核 ====================

    /**
     * 解压 tar.gz 到目标目录。四重防护（规范 §2/§10）<b>逐条目在写入之前</b>检查：
     * 拒绝 symlink / hardlink / 绝对路径 / 含 {@code ..} 的条目，条目数与解压总体积封顶。
     * 恢复 POSIX exec 位（graphviz 的 dot 等）；非 posix 文件系统跳过。
     */
    void extract(Path archive, Path destDir) {
        try {
            Files.createDirectories(destDir);
            Path canonicalDest = destDir.toRealPath();
            int entries = 0;
            long unpacked = 0;
            try (TarArchiveInputStream tin = new TarArchiveInputStream(
                    new GzipCompressorInputStream(Files.newInputStream(archive)))) {
                TarArchiveEntry e;
                while ((e = tin.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES) {
                        throw new IllegalStateException(LangText.of(
                                "压缩包条目数超过上限（" + MAX_ENTRIES + "）",
                                "Archive exceeds the entry limit (" + MAX_ENTRIES + ")"));
                    }
                    if (e.isSymbolicLink() || e.isLink()) {
                        throw new IllegalStateException(LangText.of(
                                "压缩包含链接条目，拒绝解压: ", "Archive contains a link entry; extraction refused: ") + e.getName());
                    }
                    if (!e.isDirectory() && !e.isFile()) {
                        throw new IllegalStateException(LangText.of(
                                "压缩包含非常规条目，拒绝解压: ", "Archive contains a non-regular entry; extraction refused: ") + e.getName());
                    }
                    String name = e.getName();
                    if (!isSafeRelPath(name)) {
                        throw new IllegalStateException(LangText.of(
                                "压缩包含非法路径，拒绝解压: ", "Archive contains an unsafe path; extraction refused: ") + name);
                    }
                    Path dest = canonicalDest.resolve(name).normalize();
                    if (!dest.startsWith(canonicalDest)) {
                        throw new IllegalStateException(LangText.of(
                                "压缩包路径越界，拒绝解压: ", "Archive path escapes the target directory; extraction refused: ") + name);
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(dest);
                        continue;
                    }
                    unpacked += Math.max(e.getSize(), 0);
                    if (unpacked > MAX_UNPACKED_BYTES) {
                        throw new IllegalStateException(LangText.of(
                                "解压后体积超过上限（500 MB）", "Unpacked size exceeds the 500 MB limit"));
                    }
                    Files.createDirectories(dest.getParent());
                    try (OutputStream out = Files.newOutputStream(dest)) {
                        tin.transferTo(out);
                    }
                    restoreExecBit(dest, e.getMode());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("解压失败: ", "Extraction failed: ") + e.getMessage());
        }
    }

    private static void restoreExecBit(Path file, int mode) {
        if ((mode & 0111) == 0) return;
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等非 POSIX 文件系统没有 exec 位的概念，跳过
        }
    }

    /**
     * 按包内 {@code contents.sha256}（每行 {@code <hex>  <相对路径>}）逐文件复核。
     * 签名已经从密码学上覆盖了压缩包内容，这一步是落盘完整性的事后可审计凭据。
     */
    void verifyContents(Path dir) {
        Path list = dir.resolve(CONTENTS_LIST);
        if (!Files.isRegularFile(list)) {
            throw new IllegalStateException(LangText.of(
                    "组件缺少 contents.sha256 清单", "Component is missing the contents.sha256 list"));
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(list, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("读取 contents.sha256 失败: ", "Failed to read contents.sha256: ") + e.getMessage());
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2) {
                throw new IllegalStateException(LangText.of("contents.sha256 格式错误: ", "Malformed contents.sha256 line: ") + trimmed);
            }
            String expected = parts[0];
            String rel = parts[1].trim();
            if (!isSafeRelPath(rel)) {
                throw new IllegalStateException(LangText.of("contents.sha256 含非法路径: ", "contents.sha256 contains an unsafe path: ") + rel);
            }
            Path file = dir.resolve(rel).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                throw new IllegalStateException(LangText.of("清单里的文件不存在: ", "File listed in contents.sha256 is missing: ") + rel);
            }
            if (!sha256Hex(file).equalsIgnoreCase(expected)) {
                throw new IllegalStateException(LangText.of("文件校验失败: ", "File verification failed: ") + rel);
            }
        }
    }

    // ==================== 封禁 ====================

    @PostConstruct
    public void onStartup() {
        if (!props.isEnabled()) return;
        Thread t = new Thread(this::syncRevoked, "native-pack-revocation-init");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = DAY_MS, initialDelay = DAY_MS)
    public void scheduledSyncRevoked() {
        if (props.isEnabled()) syncRevoked();
    }

    /**
     * 拉封禁表并对已装 pack 打标（{@code current.json} 的 revoked 位）。
     * 打标后资源解析链对它视而不见；<b>不删本地文件</b>（与插件封禁同理，防误封丢数据）。
     *
     * @return 本次新打上封禁标记的 pack id
     */
    public List<String> syncRevoked() {
        List<RevokedPack> revoked;
        try {
            revoked = fetchRevoked();
        } catch (Exception e) {
            // 官网未部署该端点时 404，静默跳过，不刷错误日志
            log.debug("Pack revocation sync skipped: {}", e.getMessage());
            return List.of();
        }
        List<String> hit = new ArrayList<>();
        for (RevokedPack r : revoked) {
            if (r.id() == null) continue;
            JSONObject current = readCurrent(r.id());
            if (current == null) continue;
            String installed = current.getStr("version");
            boolean matches = "*".equals(r.version()) || r.version() == null || r.version().equals(installed);
            if (!matches || current.getBool("revoked", false)) continue;
            writeCurrent(r.id(), installed, true);
            statuses.remove(r.id());
            hit.add(r.id());
            log.warn("Native pack {} v{} revoked by platform: {}", r.id(), installed, r.reason());
        }
        return hit;
    }

    List<RevokedPack> fetchRevoked() {
        byte[] body;
        try {
            body = httpGetBytes(props.getRevokedUrl());
        } catch (Exception e) {
            throw new IllegalStateException("revocation list unreachable: " + e.getMessage());
        }
        List<RevokedPack> result = new ArrayList<>();
        for (Object o : JSONUtil.parseArray(new String(body, StandardCharsets.UTF_8))) {
            JSONObject j = (JSONObject) o;
            result.add(new RevokedPack(j.getStr("id"), j.getStr("version"), j.getStr("reason"), j.getStr("revokedAt")));
        }
        return result;
    }

    // ==================== 落盘细节 ====================

    Path packsRoot() {
        return Paths.get(props.getDir()).toAbsolutePath().normalize();
    }

    /** current.json 指向的、带完成标记的、未被封禁的版本目录 */
    private Optional<Path> currentVersionDir(String packId) {
        if (packId == null || !PACK_ID.matcher(packId).matches()) return Optional.empty();
        JSONObject current = readCurrent(packId);
        if (current == null || current.getBool("revoked", false)) return Optional.empty();
        String version = current.getStr("version");
        if (version == null || version.isBlank() || !isSafeSegment(version)) return Optional.empty();
        Path dir = packsRoot().resolve(packId).resolve(version);
        return Files.isRegularFile(dir.resolve(COMPLETE_MARKER)) ? Optional.of(dir) : Optional.empty();
    }

    private JSONObject readCurrent(String packId) {
        Path f = packsRoot().resolve(packId).resolve(CURRENT_JSON);
        if (!Files.isRegularFile(f)) return null;
        try {
            return JSONUtil.parseObj(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("资源包指针 {} 无法解析: {}", f, e.getMessage());
            return null;
        }
    }

    /** 指针原子写（tmp + rename，overlay.js 同款） */
    private void writeCurrent(String packId, String version, boolean revoked) {
        Path dir = packsRoot().resolve(packId);
        Path target = dir.resolve(CURRENT_JSON);
        Path tmp = dir.resolve(CURRENT_JSON + ".tmp");
        JSONObject j = JSONUtil.createObj()
                .set("version", version)
                .set("activatedAt", Instant.now().toString())
                .set("revoked", revoked);
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("写入资源包指针失败: ", "Failed to write the pack pointer: ") + e.getMessage());
        }
    }

    /** 卸载时连带清掉这个 pack 的安装工作区（含未完成的 .part 断点文件） */
    private void deleteStaging(String packId) {
        Path staging = packsRoot().resolve(".staging");
        if (!Files.isDirectory(staging)) return;
        try (var stream = Files.list(staging)) {
            stream.filter(p -> p.getFileName().toString().startsWith(packId + "-"))
                    .forEach(p -> FileUtil.del(p.toFile()));
        } catch (IOException e) {
            log.warn("清理安装工作区失败: {}", e.getMessage());
        }
    }

    /** 只保留 current 一版：draw.io 级别的体积不值得本地存两份（规范 §4.2-5） */
    private void pruneOtherVersions(String packId, String keep) {
        Path dir = packsRoot().resolve(packId);
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equals(keep))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> FileUtil.del(p.toFile()));
        } catch (IOException e) {
            log.warn("清理旧版本目录失败: {}", e.getMessage());
        }
    }

    // ==================== 平台与工具函数 ====================

    /** 当前平台键（与 desktop/bundled/${os}-${arch} 命名一致） */
    protected String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String o = os.contains("mac") || os.contains("darwin") ? "mac"
                : os.contains("win") ? "win" : "linux";
        String a = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
        return o + "-" + a;
    }

    /** 「匹配自身平台 ∪ *」 */
    List<Component> componentsForPlatform(Manifest m) {
        String self = platform();
        List<Component> out = new ArrayList<>();
        for (Component c : m.components()) {
            List<String> platforms = c.platforms();
            if (platforms == null || platforms.isEmpty() || platforms.contains("*") || platforms.contains(self)) {
                out.add(c);
            }
        }
        return out;
    }

    private PackStatus liveStatus(String packId) {
        return statuses.computeIfAbsent(packId, id -> {
            PackStatus st = status(id);
            st.setId(id);
            return st;
        });
    }

    private static long totalSize(List<Component> components) {
        long total = 0;
        for (Component c : components) total += Math.max(c.size(), 0);
        return total;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    static String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("哈希计算失败: ", "Failed to compute hash: ") + e.getMessage());
        }
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static List<String> strList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (Object o : arr) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /** 单个路径段：非空、无分隔符、无 .. */
    static boolean isSafeSegment(String s) {
        if (s == null || s.isBlank() || s.length() > 200) return false;
        if (s.contains("/") || s.contains("\\") || s.equals(".") || s.equals("..")) return false;
        return !s.startsWith(".");
    }

    /** 压缩包/清单里的相对路径 */
    static boolean isSafeRelPath(String p) {
        if (p == null || p.isBlank() || p.length() > 1024) return false;
        if (p.startsWith("/") || p.contains("\\") || p.matches("^[a-zA-Z]:.*")) return false;
        for (String seg : p.split("/")) {
            if (seg.equals("..") || seg.isBlank()) return false;
        }
        return true;
    }

    static boolean isSemver(String v) {
        return v != null && v.matches("^\\d+\\.\\d+(\\.\\d+)?.*$");
    }

    static int compareSemver(String a, String b) {
        String[] pa = (a == null ? "0.0.0" : a).split("\\.");
        String[] pb = (b == null ? "0.0.0" : b).split("\\.");
        for (int i = 0; i < 3; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void requireValidId(String id) {
        if (id == null || !PACK_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(LangText.of("非法资源包 id: ", "Invalid pack ID: ") + id);
        }
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new IllegalStateException(LangText.of(
                    "资源包分发已关闭（ai.packs.enabled=false）",
                    "Native pack distribution is disabled (ai.packs.enabled=false)"));
        }
    }

    @PreDestroy
    public void shutdown() {
        installer.shutdownNow();
    }

    // ==================== HTTP seam（单测覆写；测试用本地 HTTP 桩） ====================

    protected HttpClient httpClient() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
                c = httpClient;
            }
        }
        return c;
    }

    /** 小体量 GET（manifest / sig / 封禁表）；非 200 抛 IOException */
    protected byte[] httpGetBytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<byte[]> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    /** 大文件 GET，{@code from > 0} 时带 Range 头请求断点续传 */
    protected HttpResponse<InputStream> httpGetRange(String url, long from)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET();
        if (from > 0) {
            b.header("Range", "bytes=" + from + "-");
        }
        return httpClient().send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }
}
