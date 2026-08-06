package com.checkba.service.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * conversationId 服务端签发登记簿（2026-08 安全审计遗留项）。
 *
 * 背景：conversationId 历史上由客户端自造（conv-毫秒时间戳），可猜；
 * 空会话在首条消息落库前，任何登录用户都能抢占（canUseConversation 对无消息会话放行）。
 * 本服务把「谁签发的会话归谁」登记在内存里，在首条消息落库前就锁定归属，关掉抢占窗口。
 *
 * 与 AgentRunStateService / SseEmitterService 一样是进程内单实例假设（ConcurrentHashMap），
 * 与现状部署形态一致；多实例部署时需要外置存储，届时一并改。
 *
 * 登记项惰性 24 小时过期：首条消息落库后归属判定落到 DB（ProjectAiMessageService），
 * 登记项即失去作用，过期清理只为防 Map 无限涨。
 */
@Service
public class ConversationIssuanceService {

    /** 登记项存活时长：24 小时（首条消息落库后 DB 接管归属，登记项过期无害）。 */
    static final long TTL_MILLIS = 24L * 60 * 60 * 1000;

    private final boolean issuanceRequired;
    private final boolean localMode;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();

    record Registration(Long ownerUserId, Long projectId, long issuedAtMillis) {}

    @Autowired
    public ConversationIssuanceService(
            @Value("${security.conversation-issuance-required:false}") boolean issuanceRequired,
            @Value("${security.local-mode:false}") boolean localMode) {
        this(issuanceRequired, localMode, Clock.systemUTC());
    }

    ConversationIssuanceService(boolean issuanceRequired, boolean localMode, Clock clock) {
        this.issuanceRequired = issuanceRequired;
        this.localMode = localMode;
        this.clock = clock;
    }

    /**
     * 签发一个新会话 ID 并登记归属。
     * 格式 conv-&lt;毫秒&gt;-&lt;16位随机base64url&gt;：保留 conv- 前缀兼容既有格式约定，
     * 随机段 12 字节（96 bit）不可猜。
     */
    public String issue(Long userId, Long projectId) {
        if (userId == null) {
            throw new IllegalArgumentException("签发会话必须有归属用户");
        }
        purgeExpired();
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String conversationId = "conv-" + clock.millis() + "-"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        registrations.put(conversationId, new Registration(userId, projectId, clock.millis()));
        return conversationId;
    }

    /** 登记的归属用户；未登记或登记已过期返回 null。 */
    public Long ownerOf(String conversationId) {
        Registration reg = liveRegistration(conversationId);
        return reg == null ? null : reg.ownerUserId();
    }

    /** 该会话是否有未过期的登记。 */
    public boolean isRegistered(String conversationId) {
        return liveRegistration(conversationId) != null;
    }

    /**
     * 是否强制「空会话必须先经服务端签发」：
     * security.conversation-issuance-required=true（官方云配）时开启；
     * local-mode（单机免登，回环监听）恒不强制——桌面端自造 ID 流程不受影响。
     * 只约束尚无消息的会话；已有消息的会话归属由 DB 判定，不受本开关影响
     * （否则进程重启丢登记后所有历史会话都会被挡）。
     */
    public boolean enforceIssuance() {
        return issuanceRequired && !localMode;
    }

    private Registration liveRegistration(String conversationId) {
        if (conversationId == null) return null;
        Registration reg = registrations.get(conversationId);
        if (reg == null) return null;
        if (clock.millis() - reg.issuedAtMillis() > TTL_MILLIS) {
            registrations.remove(conversationId, reg);
            return null;
        }
        return reg;
    }

    private void purgeExpired() {
        long now = clock.millis();
        registrations.entrySet().removeIf(e -> now - e.getValue().issuedAtMillis() > TTL_MILLIS);
    }

    int registrationCount() {
        return registrations.size();
    }
}
