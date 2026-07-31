package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class DeviceTokenService {

    public static final String TOKEN_PREFIX = "awdt_";

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
        t.setName(name == null || name.isBlank() ? "未命名设备" : name.trim());
        t.setCreatedAt(LocalDateTime.now());
        t = repository.save(t);
        return new IssuedToken(t.getId(), plaintext);
    }

    /** 未命中返回 null——调用方（静态鉴权入口）把 null 当未登录处理。 */
    public Long resolveUserId(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(TOKEN_PREFIX)) return null;
        return repository.findByTokenHash(sha256(plaintext))
                .map(t -> {
                    t.setLastUsedAt(LocalDateTime.now());
                    repository.save(t);
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
