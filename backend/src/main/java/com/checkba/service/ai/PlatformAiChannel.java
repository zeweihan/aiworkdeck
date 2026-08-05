package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * 平台 AI 通道「AI Workdeck 云端」（Spec §3）。
 *
 * 用户不必自备 OpenRouter key：连接账户后由官网 provision 一把带额度上限的 runtime key
 * （POST /api/account/ai-key），桌面端拿来直连 OpenRouter。额度强制在 OpenRouter 侧执行
 * （key 自带 limit，超限 402），桌面端不做也做不了额度校验。
 *
 * key 缓存在 {@code ~/.aiworkdeck/platform-ai-key.json}（0600）。官网端点是幂等的，
 * 缓存只是省一次往返；官网撤销重发后本地 clearCache 即可重新拉取。
 *
 * 未连接账户时该通道不可用（{@link #isAvailable()} 为 false，前端据此不展示为可选供应商）。
 */
@Service
@Slf4j
public class PlatformAiChannel {

    private final AccountService accountService;
    private final Path keyFile;
    // 解析失败的异常 message 不许带原文——这个文件里是 provision 出来的 OpenRouter 明文密钥
    private final ObjectMapper objectMapper = AccountService.stateMapper();

    private volatile Cached memory;

    public PlatformAiChannel(
            AccountService accountService,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir) {
        this.accountService = accountService;
        this.keyFile = Path.of(stateDir, "platform-ai-key.json");
    }

    /** 持久化结构：~/.aiworkdeck/platform-ai-key.json */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Cached {
        public String openrouterKey;
        public Double limitUsd;
        public String fetchedAt;
    }

    /** 账户已连接才可能有平台通道。不发网络请求。 */
    public boolean isAvailable() {
        return accountService.isConnected();
    }

    /**
     * 取平台通道密钥：内存缓存 → 磁盘缓存 → 向官网请求。
     *
     * @throws AccountException NOT_CONNECTED（未连接账户）/ CONFLICT（未分配 AI 额度）/ NETWORK
     */
    public String apiKey() {
        Cached cached = current();
        if (cached != null) return cached.openrouterKey;
        return fetch().openrouterKey;
    }

    /** 当前额度上限（美元）；未取到返回 null。不触发网络请求。 */
    public Double limitUsd() {
        Cached cached = current();
        return cached == null ? null : cached.limitUsd;
    }

    /**
     * 模型实例缓存用的密钥指纹。key 换了指纹就变，
     * {@link ChatModelFactory} 因此不会把旧 key 建的模型实例一直用到进程重启。
     */
    public String keyFingerprint() {
        Cached cached = current();
        if (cached == null || cached.openrouterKey == null) return "none";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(cached.openrouterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return "none";
        }
    }

    /** 断开账户或官网撤销重发后调用。 */
    public synchronized void clearCache() {
        memory = null;
        try {
            Files.deleteIfExists(keyFile);
        } catch (Exception e) {
            log.warn("平台 AI 通道密钥缓存清除失败: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private synchronized Cached current() {
        if (memory != null) return memory;
        try {
            if (Files.exists(keyFile)) {
                Cached cached = objectMapper.readValue(Files.readAllBytes(keyFile), Cached.class);
                if (cached != null && cached.openrouterKey != null && !cached.openrouterKey.isBlank()) {
                    memory = cached;
                    return memory;
                }
            }
        } catch (Exception e) {
            log.warn("平台 AI 通道密钥缓存读取失败，将重新获取: {}", e.getMessage());
        }
        return null;
    }

    private synchronized Cached fetch() {
        Map<String, Object> body = accountService.fetchAiKey();
        Cached cached = new Cached();
        cached.openrouterKey = String.valueOf(body.get("openrouterKey"));
        Object limit = body.get("limitUsd");
        cached.limitUsd = limit instanceof Number n ? n.doubleValue() : null;
        cached.fetchedAt = Instant.now().toString();
        try {
            Files.createDirectories(keyFile.getParent());
            Files.write(keyFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cached));
            AccountService.restrictPermissions(keyFile);
        } catch (Exception e) {
            // 落盘失败不致命：本次进程内仍可用，下次重启再拉一次
            log.warn("平台 AI 通道密钥缓存写入失败: {}", e.getMessage());
        }
        memory = cached;
        return cached;
    }
}
