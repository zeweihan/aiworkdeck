package com.checkba.service.ai;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.service.LangText;
import com.checkba.service.market.MarketPurchaseGate;
import com.checkba.service.market.RegistryReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 在线插件广场客户端（官网注册表，配置 ai.plugins.registry-url）。
 * 契约见 docs/PLUGIN_DISTRIBUTION.md §6/§7。
 *
 * 与 Skill 广场的本质区别：插件是可执行代码、与宿主同 JVM 同权限，因此
 * **安装前必须验签**——平台用 Ed25519 私钥对「包内每个文件的 SHA-256」签名，
 * 这里用内置公钥验证，任一环节不符即中止且不落任何文件。
 *
 * 安装后插件默认处于禁用状态，需用户在广场手动启用。配合
 * PluginService「禁用即不加载 JAR」，这意味着用户确认之前插件代码一行都不会执行。
 *
 * <h3>付费项（PR-D）</h3>
 * bundle 与 file 两个端点对 {@code priceCents > 0} 的插件都要求 {@code Authorization: Bearer awdk_}
 * 且已购，否则 402。判定与文案统一在 {@link MarketPurchaseGate}；
 * <b>付费与否不改变验签链路</b>——签名、逐文件 SHA-256 比对一步不少。
 * 免费项（含官网旧格式缺 priceCents 字段）不查账户、不带鉴权头，下载请求逐字节与改造前一致；
 * 唯一的增量是 {@link #install} 会先查一次注册表列表拿价格（价格必须服务端自证），免费项也走这一步。
 */
@Service
@Slf4j
public class PluginMarketService {

    private static final Pattern PLUGIN_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    /** 单个文件下载上限，防御恶意注册表用超大响应打爆内存 */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final String registryUrl;
    private final String publicKeyPem;
    private final String pluginsDir;
    private final PluginService pluginService;
    private final MarketPurchaseGate purchaseGate;

    public PluginMarketService(
            @Value("${ai.plugins.registry-url:https://www.aiworkdeck.com/api/registry/plugins}") String registryUrl,
            @Value("${ai.plugins.registry-public-key:}") String publicKeyPem,
            @Value("${ai.plugins.dir:plugins}") String pluginsDir,
            PluginService pluginService,
            MarketPurchaseGate purchaseGate) {
        this.registryUrl = registryUrl;
        this.publicKeyPem = publicKeyPem;
        this.pluginsDir = pluginsDir;
        this.pluginService = pluginService;
        this.purchaseGate = purchaseGate;
    }

    /** 广场条目：注册表元数据 + 本地是否已安装 */
    @lombok.Data
    public static class MarketPluginView {
        private String id;
        private String name;
        private String description;
        private String icon;
        private String version;
        private String author;
        private String authorDisplayName;
        private List<String> permissions = new ArrayList<>();
        private List<Map<String, Object>> tools = new ArrayList<>();
        private Long size;
        private Integer downloads;
        private String publishedAt;
        private String homepage;
        /**
         * 售价（分），0 = 免费。**官网旧格式没有这个字段 → 反序列化得 null → 归一为 0（免费）**，
         * 绝不能因为字段缺失把免费项锁住。
         */
        private Integer priceCents;
        /** 计价方式，当前只有 "once"（一次性买断）；缺失同样按 once 处理 */
        private String pricingModel;
        private boolean installed;
        /** 已安装且本地版本低于注册表版本 */
        private boolean updatable;
        /** 付费项且账户权益里有 plugin:{id}（免费项恒 false，前端按 priceCents 判免费） */
        private boolean purchased;
    }

    /**
     * 拉取在线插件列表并标注安装 / 已购状态。
     * @throws IllegalStateException 注册表不可达或返回无法解析（调用方转 {code:1}，不阻断本地功能）
     */
    public List<MarketPluginView> listMarket() {
        String body = httpGet(registryUrl);
        List<MarketPluginView> list;
        try {
            list = JSONUtil.toList(JSONUtil.parseArray(body), MarketPluginView.class);
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("注册表返回内容无法解析: ", "Failed to parse registry response: ") + e.getMessage());
        }
        for (MarketPluginView view : list) {
            view.setPriceCents(MarketPurchaseGate.normalizePrice(view.getPriceCents()));
            if (view.getPricingModel() == null || view.getPricingModel().isBlank()) {
                view.setPricingModel("once");
            }
            view.setPurchased(view.getPriceCents() > 0 && view.getId() != null
                    && purchaseGate.purchased(MarketPurchaseGate.pluginFeature(view.getId())));
            pluginService.getPlugins().stream()
                    .filter(p -> p.getId() != null && p.getId().equals(view.getId()))
                    .findFirst()
                    .ifPresent(local -> {
                        view.setInstalled(true);
                        view.setUpdatable(compareSemver(view.getVersion(), local.getVersion()) > 0);
                    });
        }
        return list;
    }

    /** 广场列表响应带回的账户连接状态：未连接时前端把付费项显示为「需连接账户」。 */
    public boolean accountConnected() {
        return purchaseGate.accountConnected();
    }

    /**
     * 安装（或更新）一个在线插件。
     *
     * 流程严格按 docs/PLUGIN_DISTRIBUTION.md §7：验签 → 逐文件下载并比对 SHA-256
     * → 全部匹配后原子落盘 → 标记为禁用等待用户确认。任一步失败都不留半成品。
     *
     * @return 安装的插件 id
     * @throws IllegalArgumentException id 非法
     * @throws IllegalStateException 未配置公钥 / 注册表不可达 / 付费项未连接账户或未购买 / 验签失败 / 哈希不匹配 / 落盘失败
     */
    public synchronized String install(String id) {
        requireValidId(id);
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalStateException(LangText.of(
                    "未配置插件注册表公钥（ai.plugins.registry-public-key），拒绝安装",
                    "Plugin registry public key not configured (ai.plugins.registry-public-key); installation refused"));
        }

        MarketPluginView listing = findRegistryEntry(id);
        int priceCents = listing == null ? 0 : MarketPurchaseGate.normalizePrice(listing.getPriceCents());
        String itemName = listing == null || listing.getName() == null ? id : listing.getName();
        // 免费项 bearer 为 null：不带鉴权头，与改造前逐字节一致。
        // listing == null 是「价格没查到」而不是「免费」：本机有 Key 就带上，见 bearerForUnknownPrice
        String bearer = listing == null
                ? purchaseGate.bearerForUnknownPrice()
                : purchaseGate.bearerFor(priceCents, itemName);

        JSONObject bundle;
        try {
            bundle = JSONUtil.parseObj(readText(registryUrl + "/" + id + "/bundle", bearer, itemName, priceCents));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("安装清单无法解析: ", "Failed to parse install manifest: ") + e.getMessage());
        }

        String version = bundle.getStr("version");
        String publishedAt = bundle.getStr("publishedAt");
        String signature = bundle.getStr("signature");
        JSONObject filesObj = bundle.getJSONObject("files");
        if (version == null || publishedAt == null || signature == null || filesObj == null || filesObj.isEmpty()) {
            throw new IllegalStateException(LangText.of("安装清单缺少必需字段", "Install manifest is missing required fields"));
        }

        // 键按字典序重建 canonical JSON——必须与官网 lib/plugin-signing.ts 逐字节一致
        Map<String, String> files = new TreeMap<>();
        for (String key : filesObj.keySet()) {
            files.put(key, filesObj.getStr(key));
        }
        if (!verifySignature(id, version, publishedAt, files, signature)) {
            throw new IllegalStateException(LangText.of(
                    "签名验证失败，已中止安装（包可能被篡改或来源不可信）",
                    "Signature verification failed; installation aborted (the package may have been tampered with or is from an untrusted source)"));
        }
        log.info("Plugin {} v{} signature verified", id, version);

        // 先下到临时目录，全部校验通过再整体搬过去，避免半成品被扫描到
        Path staging;
        try {
            staging = Files.createTempDirectory("awd-plugin-" + id + "-");
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("创建临时目录失败: ", "Failed to create temp directory: ") + e.getMessage());
        }
        try {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String relPath = entry.getKey();
                if (!isSafeRelPath(relPath)) {
                    throw new IllegalStateException(LangText.of("清单包含非法路径: ", "Manifest contains an invalid path: ") + relPath);
                }
                byte[] data = readBinary(registryUrl + "/" + id + "/file?path=" + urlEncode(relPath),
                        bearer, itemName, priceCents);
                String actual = sha256Hex(data);
                if (!actual.equalsIgnoreCase(entry.getValue())) {
                    throw new IllegalStateException(LangText.of(
                            "文件校验失败: " + relPath + "（内容与签名清单不符）",
                            "File verification failed: " + relPath + " (content does not match the signed manifest)"));
                }
                Path dest = staging.resolve(relPath).normalize();
                if (!dest.startsWith(staging)) {
                    throw new IllegalStateException(LangText.of("清单路径越界: ", "Manifest path escapes the target directory: ") + relPath);
                }
                Files.createDirectories(dest.getParent());
                Files.write(dest, data);
            }

            File target = new File(pluginsDir, id);
            FileUtil.del(target);
            Files.createDirectories(target.toPath().getParent());
            try {
                Files.move(staging, target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                // 跨文件系统时原子移动不可用，退化为拷贝
                FileUtil.copyContent(staging.toFile(), target, true);
                FileUtil.del(staging.toFile());
            }
        } catch (RuntimeException e) {
            FileUtil.del(staging.toFile());
            throw e;
        } catch (Exception e) {
            FileUtil.del(staging.toFile());
            throw new IllegalStateException(LangText.of("写入插件文件失败: ", "Failed to write plugin files: ") + e.getMessage());
        }

        // 先置为禁用再 rescan：新装插件在用户确认前不加载任何代码
        pluginService.markDisabledBeforeLoad(id);
        pluginService.rescan();
        log.info("Installed market plugin '{}' v{} (disabled until user confirms)", id, version);
        return id;
    }

    /** 卸载：删除 plugins/<id>/ 并 rescan */
    public synchronized void uninstall(String id) {
        requireValidId(id);
        File pluginsRoot = new File(pluginsDir);
        File dir = new File(pluginsRoot, id);
        try {
            if (!dir.getCanonicalPath().startsWith(pluginsRoot.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException(LangText.of("非法插件目录: ", "Invalid plugin directory: ") + id);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(LangText.of("路径检查失败: ", "Path check failed: ") + e.getMessage());
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException(LangText.of("插件未安装: ", "Plugin not installed: ") + id);
        }
        FileUtil.del(dir);
        pluginService.rescan();
        log.info("Uninstalled plugin '{}'", id);
    }

    /** 封禁列表条目 */
    public record RevokedPlugin(String id, String version, String reason, String revokedAt) {}

    /**
     * 拉取平台封禁列表。version 为 "*" 表示该 id 全部版本被封禁。
     * @throws IllegalStateException 注册表不可达（调用方应容忍，不影响本地功能）
     */
    public List<RevokedPlugin> fetchRevoked() {
        String body = httpGet(registryUrl + "/revoked");
        List<RevokedPlugin> result = new ArrayList<>();
        try {
            for (Object o : JSONUtil.parseArray(body)) {
                JSONObject j = (JSONObject) o;
                result.add(new RevokedPlugin(
                        j.getStr("id"), j.getStr("version"), j.getStr("reason"), j.getStr("revokedAt")));
            }
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("封禁列表无法解析: ", "Failed to parse the revocation list: ") + e.getMessage());
        }
        return result;
    }

    // ==================== 签名验证 ====================

    /**
     * 重建 canonical JSON 并用内置公钥验签。
     *
     * 这个字符串必须与官网 lib/plugin-signing.ts 的 canonicalPayload() 逐字节一致：
     * 顶层键按字典序 files < id < publishedAt < version，files 内部键同样排序，无多余空白。
     */
    boolean verifySignature(String id, String version, String publishedAt,
                            Map<String, String> sortedFiles, String signatureB64) {
        try {
            // 用 LinkedHashMap 保序输出；Hutool 的 JSONObject 默认不保证顺序，故手工拼装
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", new LinkedHashMap<>(sortedFiles));
            payload.put("id", id);
            payload.put("publishedAt", publishedAt);
            payload.put("version", version);
            String canonical = JSONUtil.toJsonStr(payload);

            PublicKey key = parsePublicKey(publicKeyPem);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            log.error("Signature verification error for plugin {}: {}", id, e.getMessage());
            return false;
        }
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 清单里的相对路径同样要防逃逸——注册表也可能被攻陷 */
    static boolean isSafeRelPath(String p) {
        if (p == null || p.isBlank() || p.length() > 255) return false;
        if (p.startsWith("/") || p.startsWith("\\") || p.contains("\\") || p.matches("^[a-zA-Z]:.*")) return false;
        for (String seg : p.split("/")) {
            if (seg.equals("..") || seg.equals(".") || seg.isBlank()) return false;
        }
        return true;
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

    private static void requireValidId(String id) {
        if (id == null || !PLUGIN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(LangText.of("非法插件 id: ", "Invalid plugin ID: ") + id);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 安装前查一次注册表元数据拿价格。
     *
     * 价格必须由服务端自己确认——前端传来的 priceCents 只是显示值，拿它决定「要不要带 Bearer」
     * 就等于让客户端决定付费闸门什么时候生效。
     *
     * 列表不可达 / 条目已下架时返回 null，按免费继续尝试：真付费项官网仍会 402 兜底，
     * 而在这里直接失败会把一次网络抖动变成免费项也装不上。
     */
    private MarketPluginView findRegistryEntry(String id) {
        try {
            return listMarket().stream()
                    .filter(v -> id.equals(v.getId()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("安装前拉取注册表元数据失败，按免费项继续: {}", e.getMessage());
            return null;
        }
    }

    /** 带付费闸门的文本读取：402 翻成明确中文，其余非 200 沿用原有措辞。 */
    private String readText(String url, String bearer, String itemName, int priceCents) {
        RegistryReply reply = httpGet(url, bearer);
        if (reply.status() == 402) {
            throw purchaseGate.paymentRequired(reply.body(), itemName, priceCents);
        }
        if (reply.status() != 200) {
            throw new IllegalStateException(LangText.of("注册表请求失败 (HTTP ", "Registry request failed (HTTP ") + reply.status() + ")");
        }
        return reply.body();
    }

    /** 带付费闸门的二进制读取（含 50 MB 上限，防恶意注册表打爆内存）。 */
    private byte[] readBinary(String url, String bearer, String itemName, int priceCents) {
        RegistryReply reply = httpGetBytes(url, bearer);
        if (reply.status() == 402) {
            throw purchaseGate.paymentRequired(reply.body(), itemName, priceCents);
        }
        if (reply.status() != 200) {
            throw new IllegalStateException(LangText.of("下载失败 (HTTP ", "Download failed (HTTP ") + reply.status() + ")");
        }
        byte[] data = reply.data();
        if (data == null) {
            throw new IllegalStateException(LangText.of("下载内容为空", "Downloaded content is empty"));
        }
        if (data.length > MAX_FILE_BYTES) {
            throw new IllegalStateException(LangText.of("文件超过 50 MB 上限", "File exceeds the 50 MB limit"));
        }
        return data;
    }

    /** 无鉴权 HTTP GET 文本；非 200 直接抛（listMarket / fetchRevoked 用） */
    protected String httpGet(String url) {
        RegistryReply reply = httpGet(url, null);
        if (reply.status() != 200) {
            throw new IllegalStateException(LangText.of("注册表请求失败 (HTTP ", "Registry request failed (HTTP ") + reply.status() + ")");
        }
        return reply.body();
    }

    /**
     * HTTP GET 文本 seam（单测覆写此方法打桩）。
     * @param bearer 非空时带 {@code Authorization: Bearer}；付费项 bundle 端点需要
     */
    protected RegistryReply httpGet(String url, String bearer) {
        HttpResponse resp;
        try {
            HttpRequest req = HttpRequest.get(url).setConnectionTimeout(5000).setReadTimeout(15000);
            if (bearer != null && !bearer.isBlank()) {
                req.header("Authorization", "Bearer " + bearer);
            }
            resp = req.execute();
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("注册表不可达: ", "Registry unreachable: ") + e.getMessage());
        }
        return new RegistryReply(resp.getStatus(), resp.bodyBytes());
    }

    /**
     * HTTP GET 二进制 seam（单测覆写此方法打桩）。
     * @param bearer 非空时带 {@code Authorization: Bearer}；付费项 file 端点需要
     */
    protected RegistryReply httpGetBytes(String url, String bearer) {
        HttpResponse resp;
        try {
            HttpRequest req = HttpRequest.get(url).setConnectionTimeout(5000).setReadTimeout(60000);
            if (bearer != null && !bearer.isBlank()) {
                req.header("Authorization", "Bearer " + bearer);
            }
            resp = req.execute();
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("下载失败: ", "Download failed: ") + e.getMessage());
        }
        return new RegistryReply(resp.getStatus(), resp.bodyBytes());
    }
}
