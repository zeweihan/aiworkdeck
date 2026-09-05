package com.checkba.version;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.entity.CloudConnection;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.service.LangText;
import com.checkba.service.account.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 官方团队案件库的零配置直连（dev-board#439）。
 *
 * <p>用官网账号登录桌面端之后，律师不该再被要求「填服务器地址、填账号、填密码」——
 * 那三样他一样也不知道。本类把这一步做掉：地址由 {@link OfficialCloudEndpoint} 派生，
 * 身份用本机已有的 {@code awdk_} 账户 Key 去官方案件库换一枚长期设备令牌
 * （{@code POST {base}/api/auth/awdk-login}），换回来的结果就是一条普通的
 * {@link CloudConnection}——**往后所有协作动作与手工连接的那条路完全同形**，
 * 共享/上传/更新/裁决一行都不用改。
 *
 * <p>形状照 {@code MobileRelayClientService} 的既有先例（同一条 awdk 桥、同一套
 * 账户指纹机制）：指纹没变就复用已有令牌，换了官网账户就地重桥。
 *
 * <p>刻意**不**并进 {@link CloudSyncService}：那个类只认识 Git 与云端同步语义，
 * 把「官网账户」这层身份塞进去会让它同时依赖账户体系；而且它的构造器被四个
 * 测试类手工 new，加参数是纯 churn。
 */
@Service
public class OfficialCloudService {

    private static final Logger log = LoggerFactory.getLogger(OfficialCloudService.class);

    private final String officialBaseUrl;
    private final AccountService accountService;
    private final CloudConnectionRepository connectionRepository;
    private final CloudSyncService cloudSyncService;

    public OfficialCloudService(
            @Value("${cloud.collab.base-url:}") String configuredBaseUrl,
            @Value("${ai.account.base-url:https://www.aiworkdeck.com}") String accountBaseUrl,
            AccountService accountService,
            CloudConnectionRepository connectionRepository,
            CloudSyncService cloudSyncService) {
        // 配错地址（明文 http）在启动期就炸掉，不静默降级——同 AwdkLoginService 的做法
        this.officialBaseUrl = OfficialCloudEndpoint.resolve(configuredBaseUrl, accountBaseUrl);
        this.accountService = accountService;
        this.connectionRepository = connectionRepository;
        this.cloudSyncService = cloudSyncService;
    }

    /** 官方案件库地址；null = 本站暂不提供（国际站）。 */
    public String officialBaseUrl() {
        return officialBaseUrl;
    }

    public boolean available() {
        return officialBaseUrl != null;
    }

    /**
     * 界面用的一行状态：{@code {available, connected, serverUrl, username}}。
     * 与 {@code CloudController.connectionListItem} 同一条纪律——**绝不带 deviceToken**。
     */
    public Map<String, Object> status(Long userId) {
        Map<String, Object> m = new HashMap<>();
        m.put("available", available());
        m.put("serverUrl", officialBaseUrl);
        CloudConnection conn = existingOfficial(userId);
        m.put("connected", conn != null);
        m.put("username", conn == null ? null : conn.getUsername());
        return m;
    }

    /**
     * 连上官方案件库（幂等）：已有连接且账户没换过就直接复用；账户换了人就地重桥换令牌，
     * 不留下第二条连接（否则 CollabDialog 的案件库选择器会冒出一串同地址的死连接）。
     */
    public CloudConnection connectOfficial(Long userId) {
        String base = requireAvailable();
        if (userId == null) {
            throw new VersionException("connectOfficial 缺少本机用户 id");
        }
        String fingerprint = accountService.accountFingerprintOrNull();
        CloudConnection existing = existingOfficial(userId);
        if (existing != null && fingerprint != null
                && fingerprint.equals(existing.getAccountFingerprint())) {
            return existing;
        }
        String key = accountService.currentKeyOrNull();
        if (key == null) {
            // 文案不含「登录」「未授权」「请先」——那三个词会被读成掉线（licensing 地雷 1）
            throw VersionException.userFacing(LangText.of(
                    "这台电脑还没连上 AI WorkDeck 账户，连好账户就能用官方团队案件库了",
                    "This computer is not linked to an AI WorkDeck account yet — link one to use the official Team Case Library"));
        }
        return bridge(base, userId, key, fingerprint, existing);
    }

    /**
     * 一键放进案件库：没指定案件库就自动连官方的再共享；指定了就照它来
     * （自建/多库场景，也是 CollabDialog 在连了多个库时必须指名的那条路）。
     *
     * <p>「先开启版本记录」的守卫留在 {@link CloudSyncService#shareToCloud} 里不动。
     */
    public Map<String, Object> shareProject(long projectId, Long userId, Long connectionId) {
        long target = connectionId != null ? connectionId : connectOfficial(userId).getId();
        return cloudSyncService.shareToCloud(projectId, target, userId);
    }

    // ==================== 内部 ====================

    private String requireAvailable() {
        if (officialBaseUrl == null) {
            throw VersionException.userFacing(LangText.of(
                    "本站暂时没有官方团队案件库，这份案卷的版本记录只保存在本机",
                    "There is no official Team Case Library on this site yet — this case file's version history stays on this computer only"));
        }
        return officialBaseUrl;
    }

    private CloudConnection existingOfficial(Long userId) {
        if (officialBaseUrl == null || userId == null) return null;
        return connectionRepository.findFirstByUserIdAndServerUrl(userId, officialBaseUrl).orElse(null);
    }

    private CloudConnection bridge(String base, Long userId, String key,
                                   String fingerprint, CloudConnection existing) {
        JSONObject resp = JSONUtil.parseObj(
                httpPost(base + "/api/auth/awdk-login", JSONUtil.toJsonStr(Map.of("key", key))));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing(LangText.of(
                    "没能连上官方团队案件库：" + resp.getStr("message", "稍后再试一次"),
                    "Couldn't reach the official Team Case Library: " + resp.getStr("message", "please try again later")));
        }
        JSONObject data = resp.getJSONObject("data");
        CloudConnection conn = existing != null ? existing : new CloudConnection();
        conn.setUserId(userId);
        conn.setServerUrl(base);
        conn.setUsername(data.getStr("username"));
        conn.setDisplayName(data.getStr("displayName"));
        conn.setDeviceToken(data.getStr("token"));
        conn.setTokenId(data.getLong("tokenId", null));
        conn.setAccountFingerprint(fingerprint);
        if (conn.getCreatedAt() == null) {
            conn.setCreatedAt(LocalDateTime.now());
        }
        log.info("已连上官方团队案件库: {} user={} as {}", base, userId, conn.getUsername());
        return connectionRepository.save(conn);
    }

    /** 单测覆写此 seam 打桩，形制同 CloudSyncService.httpPost。 */
    protected String httpPost(String url, String jsonBody) {
        HttpRequest req = HttpRequest.post(url)
                .header("Content-Type", "application/json")
                .body(jsonBody)
                .setConnectionTimeout(5000)
                .setReadTimeout(15000);
        try (HttpResponse resp = req.execute()) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("官方案件库请求失败 (HTTP " + resp.getStatus() + ")");
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("官方案件库不可达: " + e.getMessage(), e);
        }
    }
}
