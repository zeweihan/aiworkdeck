package com.checkba.service.mobile;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.UserSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 启动期对账：作废「手机号已被转走的孤儿账号」的登录会话（dev-board#95）。
 *
 * <h3>要治的是什么</h3>
 * 手机端账号归一（dev-board#30）的做法是：官网账户带着经短信验证的手机号来桥接时，
 * {@code UserService.claimPhoneFromWebsite} 把号码从原持有者转到桥接账号名下，
 * <b>并作废原持有者的全部会话</b>（dev-board#75），逼手机端重新走短信登录、落到归一后的账号。
 *
 * <p>但那条作废是<b>认领那一刻</b>的一次性动作。在它上线之前就已经被拆开的账号，
 * 号码早就转走了、认领不会再发生一次，于是原持有者的会话<b>永远不会被作废</b>——
 * 而它又不会自己过期（7 天滑动过期，手机端每次轮询都在续命）。
 *
 * <p>后果不是报错，是<b>静默的空</b>：{@code GET /api/mobile/projects} 按 userId 取目录，
 * 孤儿账号名下什么都没有，返回一个合法的空数组。手机端显示「没有项目」，
 * 没有任何提示说「你登在另一个账号上」，重进多少次都一样。
 * 2026-08-21 现场实证：桌面端每隔几分钟就把 10 个项目推到 user 3 名下，
 * 而手机端手里那张会话属于 user 4，从 8-20 09:20 一直用到第二天，一个项目都读不到。
 *
 * <h3>怎么认出孤儿账号</h3>
 * 靠 {@code findOrCreateByPhone} 留下的两个印记，缺一不可：
 * <ul>
 *   <li>{@code displayName} 是<b>掩码手机号</b>（{@code 186****1590}）——只有短信登录建号会这么写；</li>
 *   <li>{@code phone} 为空——号码已经被 {@code claimPhoneFromWebsite} 转走了。</li>
 * </ul>
 * 两条同时成立 = 这个账号唯一的登录入口（用那个号码收短信）已经指向别人了，
 * 它的会话再也不可能被合法地重新签发，作废掉是安全的。
 *
 * <p>只匹配 displayName 不看 phone 会误伤正常的短信登录账号；
 * 只看 phone 为空会误伤 admin、官网桥接账号这些本来就没绑号的。
 *
 * <p>幂等：作废过就没有会话了，之后每次启动都是 0 条。
 */
@Service
@Slf4j
public class OrphanPhoneSessionReconciler implements CommandLineRunner {

    /** SmsAuthService.maskPhone 的产物形状：前 3 位 + **** + 后 4 位。 */
    private static final Pattern MASKED_PHONE = Pattern.compile("^1\\d{2}\\*{4}\\d{4}$");

    private final UserRepository userRepository;
    private final UserSessionService userSessionService;

    public OrphanPhoneSessionReconciler(UserRepository userRepository,
                                        UserSessionService userSessionService) {
        this.userRepository = userRepository;
        this.userSessionService = userSessionService;
    }

    @Override
    public void run(String... args) {
        reconcile();
    }

    /** @return 被作废的会话总数（供测试与日志用） */
    public long reconcile() {
        long revoked = 0;
        int accounts = 0;
        List<User> all;
        try {
            all = userRepository.findAll();
        } catch (RuntimeException e) {
            // 对账是顺手活，读不到用户表不该拦住启动
            log.warn("孤儿手机账号对账跳过：读取用户表失败 {}", e.toString());
            return 0;
        }
        for (User u : all) {
            if (!isOrphanPhoneAccount(u)) continue;
            try {
                long n = userSessionService.revokeAllForUser(u.getId());
                if (n > 0) {
                    accounts++;
                    revoked += n;
                    log.info("孤儿手机账号 {}（{}）的号码已转走，作废其 {} 条遗留会话——"
                            + "手机端会被迫重新短信登录，落到归一后的账号",
                            u.getId(), u.getDisplayName(), n);
                }
            } catch (RuntimeException e) {
                log.warn("作废孤儿账号 {} 的会话失败：{}", u.getId(), e.toString());
            }
        }
        if (accounts > 0) {
            log.info("孤儿手机账号对账完成：{} 个账号、{} 条会话", accounts, revoked);
        }
        return revoked;
    }

    /** 掩码手机号做 displayName + phone 已空 = 号码被转走的短信登录孤儿号。 */
    boolean isOrphanPhoneAccount(User u) {
        if (u == null || u.getId() == null) return false;
        if (u.getPhone() != null && !u.getPhone().isBlank()) return false;
        String display = u.getDisplayName();
        return display != null && MASKED_PHONE.matcher(display).matches();
    }
}
