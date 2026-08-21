package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class DeviceTokenService {

    public static final String TOKEN_PREFIX = "awdt_";

    /**
     * lastUsedAt 写回节流：一分钟内的重复请求不再落盘。
     * 修复病灶：resolveUserId 原来对每一次设备令牌请求都无条件 SELECT+UPDATE，
     * 云端协作客户端任何一次轮询/只读请求都会被打成一次写库，放大 DB 写负载与行锁竞争。
     * 与同一文件夹下 UserSessionService 的 TOUCH_INTERVAL 是同一手法，数值也保持一致。
     */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceTokenRepository repository;

    public DeviceTokenService(DeviceTokenRepository repository) {
        this.repository = repository;
        AuthController.registerDeviceTokenService(this);
    }

    public record IssuedToken(Long id, String plaintext) {}

    public IssuedToken issue(Long userId, String name) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String plaintext = TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        DeviceToken t = new DeviceToken();
        t.setUserId(userId);
        t.setTokenHash(sha256(plaintext));
        t.setName(name == null || name.isBlank()
                ? LangText.of("未命名设备", "Unnamed device") : name.trim());
        t.setCreatedAt(LocalDateTime.now());
        t = repository.save(t);
        return new IssuedToken(t.getId(), plaintext);
    }

    /** 未命中返回 null——调用方（静态鉴权入口）把 null 当未登录处理。 */
    public Long resolveUserId(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(TOKEN_PREFIX)) return null;
        return repository.findByTokenHash(sha256(plaintext))
                .map(t -> {
                    LocalDateTime now = LocalDateTime.now();
                    // 节流：lastUsedAt 从未写过，或已超过节流窗口，才补一次写；
                    // 窗口内的重复请求（同一设备的高频轮询）不再逐请求落库。
                    if (t.getLastUsedAt() == null || t.getLastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                        t.setLastUsedAt(now);
                        repository.save(t);
                    }
                    return t.getUserId();
                })
                .orElse(null);
    }

    public void revoke(Long userId, Long tokenId) {
        repository.findById(tokenId)
                .filter(t -> t.getUserId().equals(userId))
                .ifPresent(repository::delete);
    }

    public List<DeviceToken> listMine(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
