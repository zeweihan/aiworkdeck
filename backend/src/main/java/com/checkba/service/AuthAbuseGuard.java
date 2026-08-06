package com.checkba.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 注册闸 + 登录/注册防滥用（插件云后端加固）。
 *
 * <h3>背景</h3>
 * server 模式此前是内网团队服务器假设：开放注册、无限流。官方要托管一个 server 实例
 * 作为 Office 插件云后端，公网暴露后这两条都要闸住。
 *
 * <h3>三道闸，全部只在 server 模式生效（local-mode 一律旁路）</h3>
 * <ul>
 *   <li><b>注册闸</b>：{@code security.registration-mode: open|closed}，默认 open 保持
 *       团队服务器现状；closed 时注册返回业务错误。</li>
 *   <li><b>凭据失败锁定</b>：按 IP+用户名维度计失败次数，{@value #MAX_LOGIN_FAILURES} 次
 *       失败锁 {@code LOCKOUT} 分钟。锁定期内的请求在校验凭据之前就被拒绝，不再计数
 *       （否则轮询会把锁无限续期）。</li>
 *   <li><b>注册限频</b>：按 IP 每小时最多 {@value #MAX_REGISTRATIONS_PER_WINDOW} 个新账号。</li>
 * </ul>
 *
 * <h3>实现边界（刻意接受）</h3>
 * <ul>
 *   <li>进程内内存计数，无 Redis 依赖。<b>多实例部署时各实例独立计数，必须在前置
 *       nginx 配 limit_req 作为真正的限流层</b>；本类只是单实例基线。</li>
 *   <li>基线部署形态是 nginx 与后端同机反代，后端看到的 remoteAddr 恒为 127.0.0.1，
 *       此时 IP 维度退化为全局维度——登录锁定仍按用户名生效（这正是防爆破要的），
 *       注册限频则变成全服共享一个额度，公网生产姿态本就该 registration-mode=closed。</li>
 *   <li>不看 X-Forwarded-For（不可信，与 LocalModeAccessFilter 同一立场）。</li>
 * </ul>
 *
 * 失败文案红线：不得含「登录」「未授权」「请先」子串——前端 api.js 对 code=1 的 message
 * 做子串匹配识别掉线，命中会清本地会话（licensing 领域地雷 1）。
 */
@Service
public class AuthAbuseGuard {

    static final int MAX_LOGIN_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(10);
    /** 失败计数窗口：距上次失败超过该时长则计数归零重来。 */
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    static final int MAX_REGISTRATIONS_PER_WINDOW = 10;
    static final Duration REGISTRATION_WINDOW = Duration.ofHours(1);

    /** 内存兜底：超过该条数触发一次过期清理，防止被海量伪造维度撑爆内存。 */
    private static final int PURGE_THRESHOLD = 10_000;

    private final boolean localMode;
    private final String registrationMode;
    private final LongSupplier nowMillis;

    private final Map<String, FailureState> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> registrations = new ConcurrentHashMap<>();

    @Autowired
    public AuthAbuseGuard(
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${security.registration-mode:open}") String registrationMode) {
        this(localMode, registrationMode, System::currentTimeMillis);
    }

    /** 测试用：可控时钟。 */
    AuthAbuseGuard(boolean localMode, String registrationMode, LongSupplier nowMillis) {
        this.localMode = localMode;
        this.registrationMode = registrationMode;
        this.nowMillis = nowMillis;
    }

    // ==================== 注册闸 ====================

    /** closed 时抛业务错误。local-mode 不受影响（单机产品的注册本就不对公网暴露）。 */
    public void requireRegistrationOpen() {
        if (localMode) return;
        if (!"closed".equalsIgnoreCase(registrationMode)) return;
        throw new IllegalArgumentException("本服务器未开放自助注册，请联系服务器管理员开通账号");
    }

    // ==================== 登录失败锁定 ====================

    /** 锁定期内直接拒绝（在校验凭据之前调用）。 */
    public void checkLoginAttempt(String ip, String username) {
        if (localMode) return;
        FailureState state = loginFailures.get(loginKey(ip, username));
        if (state != null && state.lockedUntil > nowMillis.getAsLong()) {
            throw new IllegalArgumentException(
                    "尝试次数过多，该账号已临时锁定，" + LOCKOUT.toMinutes() + " 分钟后自动解除");
        }
    }

    public void recordLoginFailure(String ip, String username) {
        if (localMode) return;
        purgeIfOversized();
        long now = nowMillis.getAsLong();
        loginFailures.compute(loginKey(ip, username), (k, state) -> {
            if (state == null || now - state.lastFailureAt > FAILURE_WINDOW.toMillis()) {
                state = new FailureState();
            }
            state.count++;
            state.lastFailureAt = now;
            if (state.count >= MAX_LOGIN_FAILURES) {
                state.lockedUntil = now + LOCKOUT.toMillis();
                state.count = 0; // 锁定期满后重新给满额尝试次数
            }
            return state;
        });
    }

    public void recordLoginSuccess(String ip, String username) {
        if (localMode) return;
        loginFailures.remove(loginKey(ip, username));
    }

    // ==================== 注册限频 ====================

    public void checkRegistrationRate(String ip) {
        if (localMode) return;
        WindowCounter counter = registrations.get(ip);
        long now = nowMillis.getAsLong();
        if (counter != null
                && now - counter.windowStart <= REGISTRATION_WINDOW.toMillis()
                && counter.count >= MAX_REGISTRATIONS_PER_WINDOW) {
            throw new IllegalArgumentException("注册过于频繁，请稍后再试");
        }
    }

    public void recordRegistration(String ip) {
        if (localMode) return;
        purgeIfOversized();
        long now = nowMillis.getAsLong();
        registrations.compute(ip, (k, counter) -> {
            if (counter == null || now - counter.windowStart > REGISTRATION_WINDOW.toMillis()) {
                counter = new WindowCounter();
                counter.windowStart = now;
            }
            counter.count++;
            return counter;
        });
    }

    // ==================== 内部 ====================

    private static String loginKey(String ip, String username) {
        return (ip == null ? "-" : ip) + "|" + (username == null ? "-" : username);
    }

    private void purgeIfOversized() {
        long now = nowMillis.getAsLong();
        if (loginFailures.size() > PURGE_THRESHOLD) {
            loginFailures.entrySet().removeIf(e ->
                    e.getValue().lockedUntil < now
                            && now - e.getValue().lastFailureAt > FAILURE_WINDOW.toMillis());
        }
        if (registrations.size() > PURGE_THRESHOLD) {
            registrations.entrySet().removeIf(e ->
                    now - e.getValue().windowStart > REGISTRATION_WINDOW.toMillis());
        }
    }

    private static final class FailureState {
        int count;
        long lastFailureAt;
        long lockedUntil;
    }

    private static final class WindowCounter {
        int count;
        long windowStart;
    }
}
