package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.UserSession;
import com.checkba.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 登录会话的 DB 落库存储（替代 AuthController 里的进程内 SESSION_STORE）：
 * 重启不再全体掉线，且按 lastUsedAt 滑动过期 + 定时清理，内存不会只增不减。
 * awdt_ 设备令牌不走这里（DeviceTokenService），local-mode 免登也不碰这条路。
 */
@Service
public class UserSessionService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);

    public static final String SESSION_PREFIX = "session_";

    /** 滑动过期：距最后一次使用超过该时长即失效。 */
    static final Duration IDLE_TTL = Duration.ofDays(7);

    /** lastUsedAt 写回节流：一分钟内的重复请求不再落盘（每请求一写没有意义）。 */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    /**
     * 会话 ID 必须不可预测：它是全站唯一的持有者凭证。
     * Math.random() 背后是 48 位 LCG，攻击者用自己登录拿到的一个样本即可反解种子、
     * 推算出其他人的会话 ID（时间戳部分本就可猜），因此只能用 CSPRNG。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserSessionRepository repository;

    public UserSessionService(UserSessionRepository repository) {
        this.repository = repository;
        AuthController.registerUserSessionService(this);
    }

    /** 签发新会话，返回明文 sessionId（格式与历史内存实现一致，客户端无感）。 */
    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String plaintext = SESSION_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UserSession s = new UserSession();
        s.setUserId(userId);
        s.setTokenHash(sha256(plaintext));
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setLastUsedAt(now);
        repository.save(s);
        return plaintext;
    }

    /** 未命中或已过期返回 null——调用方（静态鉴权入口）把 null 当未登录处理。 */
    public Long resolveUserId(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(SESSION_PREFIX)) return null;
        return repository.findByTokenHash(sha256(plaintext))
                .map(s -> {
                    LocalDateTime now = LocalDateTime.now();
                    if (s.getLastUsedAt().plus(IDLE_TTL).isBefore(now)) {
                        repository.delete(s);
                        return null;
                    }
                    if (s.getLastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                        s.setLastUsedAt(now);
                        repository.save(s);
                    }
                    return s.getUserId();
                })
                .orElse(null);
    }

    /** 登出：直接删行。传入不存在的 ID 静默无事发生（与原内存实现语义一致）。 */
    public void revoke(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(SESSION_PREFIX)) return;
        repository.deleteByTokenHash(sha256(plaintext));
    }

    /** 每日清理过期会话（滑动过期在读路径已兜住，这里只是让表不积灰）。 */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 10 * 60 * 1000)
    public void purgeExpired() {
        long removed = repository.deleteByLastUsedAtBefore(LocalDateTime.now().minus(IDLE_TTL));
        if (removed > 0) {
            log.info("清理过期登录会话 {} 条", removed);
        }
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
