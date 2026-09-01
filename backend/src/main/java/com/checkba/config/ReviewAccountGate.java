package com.checkba.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * App 审核专用账号的固定验证码。
 *
 * <p><b>为什么需要这个。</b>iOS 两个 App 的登录只有验证码一条路：手机号走中国
 * 短信，邮箱走收件箱。Apple 的审核员两样都拿不到——他没有中国号码，也打不开
 * 我们的邮箱。没有这条口子，审核员卡在首屏，App Store 审核指南 2.1
 * （App Completeness）必拒。行业常规做法就是给审核账号配一个固定码，写进
 * ASC 的「审核备注」。
 *
 * <p><b>这是一个认证旁路，按认证旁路对待：</b>
 * <ul>
 *   <li>默认关。两个配置项任意一个为空即全关，不存在「配了一半」的中间态。</li>
 *   <li>只对<b>配置里那一个</b>标识生效，别的手机号/邮箱一律走原路。</li>
 *   <li>只开在免密登录（signin）那条路上，绑定手机号/绑定邮箱不走这里。</li>
 *   <li>码写错格式就<b>拒绝启动</b>——照 {@link PhoneLoginGuard} 的同一口径。
 *       一个配歪了的旁路比没有旁路更糟：它会以「审核员登不进去」的形式在
 *       几天后才暴露，而那时排查方向已经跑到客户端去了。</li>
 * </ul>
 *
 * <p><b>运维要求：</b>审核账号里不要放真实数据。谁拿到那 6 位码就能登进这一个
 * 账号，而这个码是写在 ASC 备注里给外部人看的。审核结束后把
 * {@code auth.review-account.identity} 清空即可关掉。
 *
 * <p>配置（服务器 env / application.yml，别入库）：
 * <pre>
 *   auth.review-account.identity: appreview@example.com   # 手机号或邮箱，写规范化后的形式
 *   auth.review-account.code: "246813"                    # 6 位数字
 * </pre>
 */
@Component
public class ReviewAccountGate {

    private static final Logger log = LoggerFactory.getLogger(ReviewAccountGate.class);

    private final String identity;
    private final byte[] code;

    public ReviewAccountGate(
            @Value("${auth.review-account.identity:}") String identity,
            @Value("${auth.review-account.code:}") String code) {

        String id = identity == null ? "" : identity.trim().toLowerCase();
        String cd = code == null ? "" : code.trim();

        if (id.isEmpty() || cd.isEmpty()) {
            this.identity = null;
            this.code = null;
            return;
        }
        if (!cd.matches("\\d{6}")) {
            throw new IllegalStateException(
                    "auth.review-account.code 必须是 6 位数字（当前长度 " + cd.length() + "）："
                            + "客户端的验证码输入框只收 6 位数字，配成别的形式等于这条旁路"
                            + "永远走不通，而症状要等审核员登不进去才看得见。");
        }
        this.identity = id;
        this.code = cd.getBytes(StandardCharsets.UTF_8);
        // 生产上开着这条口子是有意为之还是配漏了，只能靠这行日志区分。
        log.warn("App 审核账号旁路已启用：{} 可用固定验证码登录。审核结束后清空 "
                + "auth.review-account.identity 关闭。", masked(id));
    }

    /**
     * 明确关闭的一个实例。给不关心这条旁路的构造点用（测试基本都是），
     * 比 {@code new ReviewAccountGate("", "")} 读起来清楚：一眼看得出这里
     * 是「没有旁路」，而不是「参数忘了填」。
     */
    public static ReviewAccountGate disabled() {
        return new ReviewAccountGate("", "");
    }

    /** 这条旁路是否开着。 */
    public boolean enabled() {
        return identity != null;
    }

    /**
     * 这个标识是不是审核账号。
     *
     * <p>传进来的应当是各服务**规范化之后**的标识（手机号过 normalizePhone、
     * 邮箱过 MailRouter.normalize），配置里也要写规范化后的形式——两边不一致
     * 的表现是旁路静默失效。
     */
    public boolean matches(String normalizedIdentity) {
        if (!enabled() || normalizedIdentity == null) {
            return false;
        }
        return identity.equals(normalizedIdentity.trim().toLowerCase());
    }

    /**
     * 审核账号 + 正确的固定码。
     *
     * <p>定长比较：这 6 位码不像一次性码那样验一次就作废，它长期有效，
     * 逐位早退的比较在这里是有意义的侧信道。
     */
    public boolean accepts(String normalizedIdentity, String candidate) {
        if (!matches(normalizedIdentity) || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(code, candidate.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String masked(String id) {
        int at = id.indexOf('@');
        if (at > 1) {
            return id.charAt(0) + "***" + id.substring(at);
        }
        return id.length() <= 4 ? "***" : id.substring(0, 3) + "****" + id.substring(id.length() - 2);
    }
}
