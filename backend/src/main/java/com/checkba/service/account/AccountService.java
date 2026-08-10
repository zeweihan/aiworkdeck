package com.checkba.service.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面与官网账户的连接（商业化改造 PR-B）。
 *
 * 桌面端永远不需要登录（Spec §1）：与账户的唯一连接方式是在设置页粘贴一枚
 * 官网账户页生成的 {@code awdk_} Key。连接后可以用平台 AI 通道、同步已购权益、看余额与流水。
 *
 * 落盘：{@code ~/.aiworkdeck/account.json}（权限 0600，与 PR-A 的 license.json 同目录同规格）。
 * 出站请求一律 {@code Authorization: Bearer <key>}，5 秒超时，失败按
 * {@link AccountException.Kind} 分类——网络不可达绝不清除本地连接。
 *
 * 契约以官网仓 {@code doc/desktop-contract.md} 为准（注意两处与总 Spec 字面的偏差：
 * verify-key 的 plan 是 paid|free；ledger 返回 {@code {entries:[...]}} 而非裸数组）。
 */
@Service
@Slf4j
public class AccountService {

    private static final String KEY_PREFIX = "awdk_";

    private final com.checkba.service.site.SiteProfileService siteProfileService;
    private final Path accountFile;
    private final AccountTransport transport;
    private final ObjectMapper objectMapper = stateMapper();

    public AccountService(
            com.checkba.service.site.SiteProfileService siteProfileService,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String accountDir,
            AccountTransport transport) {
        // 基址由站点决定；协议校验（https，回环 http 例外，见 AccountEndpoint）
        // 在 SiteProfileService 的构造器里对全部站点做过一遍，与 PR-A 的 LicenseService 同源
        this.siteProfileService = siteProfileService;
        this.accountFile = Path.of(accountDir, "account.json");
        this.transport = transport;
    }

    /** 当前站点的账户服务基址。切站后当场改指向。 */
    private String baseUrl() {
        return siteProfileService.baseUrl();
    }

    /** 持久化结构：~/.aiworkdeck/account.json */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class State {
        public String key;
        public String username;
        public String displayName;
        public String connectedAt;
        public String lastSyncAt;
    }

    // ==================== 连接生命周期 ====================

    /**
     * 连接账户：调 GET /api/account/me 校验 Key，通过才落盘。
     *
     * @throws AccountException 网络不可达 / Key 无效 / 官网返回异常内容
     */
    public synchronized Map<String, Object> connect(String awdkKey) {
        String key = awdkKey == null ? "" : awdkKey.trim();
        if (key.isEmpty()) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED, "账户 Key 不能为空");
        }
        if (!key.startsWith(KEY_PREFIX)) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED,
                    "账户 Key 格式不正确，应以 awdk_ 开头（在官网账户页「桌面连接」生成）");
        }
        Map<String, Object> me = getJson("/api/account/me", key);

        State state = new State();
        state.key = key;
        state.username = str(me.get("username"));
        state.displayName = str(me.get("displayName"));
        state.connectedAt = Instant.now().toString();
        state.lastSyncAt = state.connectedAt;
        saveState(state);
        log.info("已连接 AI Workdeck 账户: {}", state.username);
        return status();
    }

    /** 断开账户连接：删除本地凭据。调用方负责一并清掉权益缓存与平台 AI Key 缓存。 */
    public synchronized Map<String, Object> disconnect() {
        try {
            Files.deleteIfExists(accountFile);
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    "断开连接失败：本地凭据文件无法删除");
        }
        return status();
    }

    /** GET /api/account/status 的数据源。**绝不返回 Key 明文**。 */
    public synchronized Map<String, Object> status() {
        State state = loadState();
        Map<String, Object> result = new HashMap<>();
        boolean connected = state.key != null && !state.key.isBlank();
        result.put("connected", connected);
        if (connected) {
            result.put("username", state.username);
            result.put("displayName", state.displayName);
            result.put("connectedAt", state.connectedAt);
            result.put("lastSyncAt", state.lastSyncAt);
            result.put("keyMasked", mask(state.key));
        }
        return result;
    }

    public synchronized boolean isConnected() {
        State state = loadState();
        return state.key != null && !state.key.isBlank();
    }

    /**
     * 已连接则返回账户 Key 明文，否则 null。
     *
     * 仅供**需要自行向官网发带鉴权请求**的服务使用（当前只有 PR-D 的广场付费项下载：
     * registry bundle/file 端点要求 {@code Authorization: Bearer awdk_}）。
     * 其余场景一律走本类的 fetchXxx 方法，不要把 Key 拿出去到处传；
     * 尤其**不得**回给前端——{@link #status()} 只暴露掩码。
     */
    public synchronized String currentKeyOrNull() {
        State state = loadState();
        return state.key == null || state.key.isBlank() ? null : state.key;
    }

    /**
     * 当前连接账户的指纹（Key 的 SHA-256 前 12 位十六进制）；未连接返回 null。
     *
     * <p>给需要回答「现在连的还是不是刚才那个账户」的地方用——机器级的缓存（平台 AI 密钥、
     * 余额判定结果）都是账户级的内容，换了账号必须作废，否则新账号会接着用上一个账号的额度。
     * <b>指纹是单向的</b>：可以比对、可以进日志，反推不出 Key，因此不受
     * {@link #currentKeyOrNull()} 那条「别把 Key 拿出去传」的限制。
     * 定义只此一处，比对两侧才不会漂。
     */
    public synchronized String accountFingerprintOrNull() {
        String key = currentKeyOrNull();
        if (key == null) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 官网数据拉取 ====================

    /** GET /api/account/me —— 余额与账户档案（余额单位是整数分）。 */
    public Map<String, Object> fetchProfile() {
        return getJson("/api/account/me", requireKey());
    }

    /**
     * GET /api/account/me，用调用方给定的 awdk_（不是本机连接的那把）。
     *
     * 供 server 模式下「代表某个已桥接用户校验其 Key」使用：Key 由调用方短暂持有、用完即弃，
     * <b>不会落到 account.json，也不会落库</b>。
     */
    public Map<String, Object> fetchProfileWith(String awdkKey) {
        return getJson("/api/account/me", awdkKey);
    }

    /**
     * GET /api/account/entitlements。
     * 契约返回 {@code {"entitlements":[{feature, purchasedAt, orderId}]}}。
     */
    public List<Map<String, Object>> fetchEntitlements() {
        Map<String, Object> body = getJson("/api/account/entitlements", requireKey());
        touchLastSync();
        return listOf(body.get("entitlements"));
    }

    /**
     * GET /api/account/ledger?limit=50。
     * 契约返回 {@code {"entries":[...]}}（**不是**总 Spec 字面写的裸数组，以官网契约文档为准）。
     */
    public List<Map<String, Object>> fetchLedger() {
        Map<String, Object> body = getJson("/api/account/ledger?limit=50", requireKey());
        return listOf(body.get("entries"));
    }

    /**
     * GET /api/account/ai-usage —— AI 额度的实时口径
     * {@code {configured, hasKey, limitUsd, usageUsd, remainingUsd, keyMasked}}。
     *
     * <p>注意：这个端点在官网仓 master 上实现了，但**没有写进** {@code doc/desktop-contract.md}，
     * 属于契约文档的缺口（已回报）。因此这里当成「可能不存在」处理：任何非 2xx 都由
     * {@link #handle} 抛 AccountException，调用方降级成「额度未知」而不是整块报错。
     *
     * <p>不用 OpenRouter 的 {@code GET /api/v1/key} 代替：那条路要求本地已经 provision 过
     * runtime key（未分配额度的账户根本没有），而这里恰恰要能回答「还没分配」。
     */
    public Map<String, Object> fetchAiUsage() {
        return getJson("/api/account/ai-usage", requireKey());
    }

    /**
     * POST /api/account/ai-key —— 取该账户的 provisioned OpenRouter runtime key。
     * 幂等：已有未禁用的 key 直接返回同一把。
     *
     * @throws AccountException CONFLICT（409 no_credits：账户 Credits 为空）
     *
     * 官网 Credits 重构后已无「先分配额度」这一步：有 Credits 就自动签发 key，
     * 所以旧的 no_allocation 分支不会再出现。留着只为兼容尚未升级的官网。
     */
    public Map<String, Object> fetchAiKey() {
        return fetchAiKeyWith(requireKey());
    }

    /**
     * 同 {@link #fetchAiKey()}，但用调用方给定的 awdk_。
     *
     * server 模式的 per-user 平台通道走这条：桥接登录/显式刷新时短暂持有该用户的 Key，
     * 换回属于他自己的 runtime key，Key 本身用完即弃（见 {@code PlatformAiKeyService}）。
     */
    public Map<String, Object> fetchAiKeyWith(String awdkKey) {
        String key = awdkKey;
        AccountTransport.Reply reply = transport.send("POST", baseUrl() + "/api/account/ai-key", key, "{}");
        if (reply.networkFailure()) {
            throw networkError();
        }
        if (reply.status() == 409) {
            String code = str(parse(reply.body()).get("error"));
            // 文案红线：不得含「登录」「未授权」「请先」——api.js 用这三个子串判掉线并清会话
            if ("no_credits".equals(code)) {
                throw new AccountException(AccountException.Kind.CONFLICT,
                        "账户 Credits 余额为空，到官网充值后即可使用平台 AI");
            }
            if ("no_allocation".equals(code)) {
                // 旧版官网才会返回；新版已无此分支
                throw new AccountException(AccountException.Kind.CONFLICT,
                        "官网尚未为该账户签发 AI 通道密钥，到官网账户页看一下");
            }
            throw new AccountException(AccountException.Kind.CONFLICT,
                    "官网 AI 额度状态异常（" + (code == null ? "未知" : code) + "），请到官网账户页查看");
        }
        if (reply.status() == 503) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    "官网 AI 额度服务暂不可用，请稍后重试");
        }
        Map<String, Object> body = handle(reply);
        if (str(body.get("openrouterKey")) == null) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    "官网返回的 AI 通道密钥为空，请稍后重试");
        }
        return body;
    }

    // ==================== 内部 ====================

    /**
     * 已连接才有 Key，否则请求根本不该发出去。
     *
     * 文案里刻意不写「请先」：前端 api.js 用「登录」「未授权」「请先」三个子串判定未登录，
     * 命中会清会话（浏览器端还会跳登录页）。账户未连接与未登录是两回事。
     */
    private String requireKey() {
        State state = loadState();
        if (state.key == null || state.key.isBlank()) {
            throw new AccountException(AccountException.Kind.NOT_CONNECTED,
                    "尚未连接 AI Workdeck 账户，可在设置页「账户与用量」粘贴账户 Key");
        }
        return state.key;
    }

    private Map<String, Object> getJson(String path, String key) {
        AccountTransport.Reply reply = transport.send("GET", baseUrl() + path, key, null);
        if (reply.networkFailure()) {
            throw networkError();
        }
        return handle(reply);
    }

    /**
     * 状态码分类。5xx 归入 NETWORK（服务器故障不等于凭据失效，不能据此清除本地连接），
     * 401/403 才是明确的鉴权失败——与 PR-A LicenseService 的判定同源。
     */
    private Map<String, Object> handle(AccountTransport.Reply reply) {
        int status = reply.status();
        if (status == 401 || status == 403) {
            throw new AccountException(AccountException.Kind.UNAUTHORIZED, unauthorizedMessage());
        }
        if (status >= 500) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    "AI Workdeck 服务器暂时不可用，请稍后重试");
        }
        if (status < 200 || status >= 300) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    "官网返回了预期外的状态（" + status + "），请稍后重试");
        }
        return parse(reply.body());
    }

    /**
     * 401/403 的文案。双站形态下必须点名站点（双主站设计 §2.6）。
     *
     * <p>另一个站的 Key 拿到本站来用必然 401，而只说「Key 无效或已被撤销」是在指控一把好 Key——
     * 用户会去官网重新生成，再撞一次同样的墙。
     *
     * <p>文案红线同 {@link #requireKey()}：不得含「登录」「未授权」「请先」三个子串，
     * 否则前端 api.js 会把它当掉线清会话。护栏
     * {@code AccountServiceTest.accountMessagesDoNotLookLikeAuthErrors}。
     */
    private String unauthorizedMessage() {
        String base = "账户 Key 无效或已被撤销，可到官网账户页重新生成";
        try {
            if (!siteProfileService.multiSite()) return base;
            String others = siteProfileService.otherSites().stream()
                    .map(com.checkba.service.site.SiteProfile::displayName)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse(null);
            if (others == null) return base;
            return base + "。当前站点是「" + siteProfileService.displayName()
                    + "」；如果你的账户注册在「" + others + "」，切换站点后重试";
        } catch (Exception e) {
            return base;
        }
    }

    private static AccountException networkError() {
        return new AccountException(AccountException.Kind.NETWORK,
                "无法连接 AI Workdeck 服务器，请检查网络后重试");
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    "官网返回的内容无法解析，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /** 拉取成功后刷新最后同步时间，供设置页展示「最近同步于 …」。 */
    private synchronized void touchLastSync() {
        State state = loadState();
        if (state.key == null) return;
        state.lastSyncAt = Instant.now().toString();
        saveState(state);
    }

    synchronized State loadState() {
        try {
            if (!Files.exists(accountFile)) return new State();
            return objectMapper.readValue(Files.readAllBytes(accountFile), State.class);
        } catch (Exception e) {
            log.warn("account.json 读取失败，按未连接处理: {}", e.getMessage());
            return new State();
        }
    }

    private void saveState(State state) {
        try {
            Files.createDirectories(accountFile.getParent());
            Files.write(accountFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
            restrictPermissions(accountFile);
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    "账户连接状态写入失败，请检查磁盘权限");
        }
    }

    /**
     * 凭据类 JSON 的共用 mapper。
     *
     * Jackson 默认开着 {@code INCLUDE_SOURCE_IN_LOCATION}：解析失败时异常 message 里会带上
     * 原文片段（{@code at [Source: (byte[])"{\"key\":\"awdk_...\""}）。account.json /
     * license.json / platform-ai-key.json 里存的都是明文密钥，而这些解析失败点普遍
     * {@code log.warn(..., e.getMessage())}——一次半截写入（崩溃/磁盘满）就足以把 0600 的密钥
     * 复制进 0644 的日志文件。关掉源文引用后 Jackson 打印 REDACTED，定位信息（行列、原因）不受影响。
     */
    public static ObjectMapper stateMapper() {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .disable(com.fasterxml.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
    }

    /**
     * 存凭据的文件默认 umask 下会落成 0644（同机他人可读），收敛为 0600。
     * 平台 AI 通道的 key 缓存文件也复用这条。
     */
    public static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows：文件默认继承用户目录 ACL，无需处理
        } catch (Exception e) {
            log.warn("account.json 权限收敛失败（文件仍可用）: {}", e.getMessage());
        }
    }

    /** 展示用掩码：只留前缀与末 4 位，够用户认出是哪把 Key，又不泄露。 */
    private static String mask(String key) {
        if (key.length() <= KEY_PREFIX.length() + 4) return KEY_PREFIX + "****";
        return KEY_PREFIX + "****" + key.substring(key.length() - 4);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
