package com.checkba.service.mobile;

import com.checkba.config.ReviewAccountGate;
import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 手机端统一账户余额与充值（dev-board#425，spec
 * {@code aiworkdeck_mobile/docs/specs/2026-09-04-mobile-recharge-design.md} §3.2）。
 *
 * <p><b>这条路与 {@code AccountController}/{@code AccountService} 是两回事</b>：那条是
 * 机器级单例（{@code ~/.aiworkdeck/account.json}，{@code MachineAccountGuard} 在 server 模式下
 * 只放行 admin），充的是"这台服务器连的那个账户"，与调用者的 userId 无关。手机端是多租户，
 * 复用它等于给 A 的钱记到 B 头上，所以这里从头到尾按 userId 走独立路径。
 *
 * <p>多租户纪律沿用 licensing-billing.md 第 17 条并且更严——充值涉及真金白银，比 AI 额度
 * 更不能有"拿错账户扣钱"的回落分支：
 * <ul>
 *   <li>accountId 只有两个来源：{@code account_binding} 里已有的绑定，或用<b>服务端 User 实体上
 *       已验证的</b>手机号/邮箱向官网 resolve 换来的。<b>绝不接受请求体传入</b>
 *       （做法同 {@link MobileTransferService#requireAccountId}）。</li>
 *   <li>User 既无手机号也无已验证邮箱 → 报错，<b>不回落任何机器级账户</b>。</li>
 *   <li>App 审核专用账号（{@link ReviewAccountGate}）不允许桥接、不允许充值——否则会给
 *       Apple/微信的审核员在官网建出一个真账户。</li>
 *   <li>解析出的 accountId 若已绑给别的 userId，<b>拒绝，不改绑</b>。</li>
 * </ul>
 *
 * <p><b>读余额永不建号</b>（复审 C1）：{@code resolve} 的 {@code create} 位只在
 * {@link #createRecharge}（用户显式发起充值）这一条路上为 true，{@link #balance} 与
 * {@link #queryRecharge} 一律 false。第一版是无条件建号的，而 iOS 设置页的 {@code .task}
 * 无条件读一次余额——「新用户打开设置页」这个纯读动作就会在官网建出一行含明文手机号的真账户，
 * 用户全程无感知、未同意；更糟的是 App 的注销流程只删 Java 侧的 app_users 与 account_binding，
 * 从不通知官网，内部口也没有 delete action，于是<b>App 自己建的账号，App 内没有任何路径能删掉</b>，
 * 直接撞 App Store 5.1.1(v) 与个人信息保护法的删除权。本期没有充值界面，所以实际上一次号都不会建。
 *
 * <p><b>充值总开关</b>（复审 N1）：{@code mobile.billing.recharge-enabled} 默认 false，
 * 关时 {@link #createRecharge} 与 {@link #queryRecharge} 在做<b>任何</b>别的事情之前短路成
 * {@link MobileBillingKind#DISABLED}，不解析身份、不发上游请求。上一条说的「本期没有充值界面
 * 所以一次号都不会建」<b>不是护栏</b>——{@code POST /api/mobile/billing/recharge} 是活的端点，
 * 任何持有有效 {@code X-Session-Id} 的人直接打它就会走到 {@code create=true}。
 * <b>这个开关要等 dev-board#434（官网账户注销传导）落地后才允许打开</b>，
 * 在那之前打开就等于把 App Store 5.1.1(v) 那条重新放出来。
 *
 * <p>失败一律带 {@link MobileBillingKind} 判别位（复审 C2），信封里是 {@code kind} 字段，
 * 四端按它分支——第一版只送 message，三端于是各自去猜（安卓硬编码中文串、小程序判
 * {@code code === -1}），全都判错。
 *
 * <p>余额读取带 30 秒 TTL 缓存，<b>缓存键是 userId</b>。现有 {@code AccountService} 用机器指纹
 * 做缓存 owner 校验，那套在多用户场景下会串账，一个字都不能照搬。
 */
@Service
@Slf4j
public class MobileBillingService {

    /** 余额缓存 TTL：短到"充完值刷一下就看得见"，长到能挡住列表页的连续刷新。 */
    static final Duration BALANCE_TTL = Duration.ofSeconds(30);

    /** 缓存条目上限：超过就顺手清一遍过期项，别让长跑进程攒出一张只增不减的表。 */
    private static final int MAX_CACHE_ENTRIES = 10_000;

    /**
     * 幂等键围栏：它会进官网 {@code orders} 表的 {@code UNIQUE(userId, idempotencyKey)}，
     * 收口成 UUID/短串形态，理由同 {@code MobileTransferService.REQUEST_ID}。
     */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    /** 商户订单号围栏：官网侧生成的形态，只做长度与字符集把关。 */
    private static final Pattern OUT_TRADE_NO = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final MobileBillingClient billing;
    private final AccountBindingRepository accountBindingRepository;
    private final UserRepository userRepository;
    private final ReviewAccountGate reviewAccount;

    /** 充值下单/查单的总开关，默认关，见 {@link #requireRechargeEnabled()}。 */
    private final boolean rechargeEnabled;

    private final Map<Long, CachedBalance> balanceCache = new ConcurrentHashMap<>();

    private record CachedBalance(MobileBillingClient.BalanceResult value, long expiresAtMillis) {
        boolean fresh(long nowMillis) {
            return expiresAtMillis > nowMillis;
        }
    }

    public MobileBillingService(MobileBillingClient billing,
                                AccountBindingRepository accountBindingRepository,
                                UserRepository userRepository,
                                ReviewAccountGate reviewAccount,
                                @Value("${mobile.billing.recharge-enabled:false}") boolean rechargeEnabled) {
        this.billing = billing;
        this.accountBindingRepository = accountBindingRepository;
        this.userRepository = userRepository;
        this.reviewAccount = reviewAccount;
        this.rechargeEnabled = rechargeEnabled;
    }

    // ==================== 对外 ====================

    /**
     * 余额（带 30 秒 TTL 缓存，键为 userId）。
     *
     * <p><b>纯读动作，{@code create=false}：查无此账户就报 NOT_CONNECTED，绝不建号。</b>
     */
    public MobileBillingClient.BalanceResult balance(Long userId) {
        long now = System.currentTimeMillis();
        CachedBalance hit = balanceCache.get(userId);
        if (hit != null && hit.fresh(now)) {
            return hit.value();
        }
        MobileBillingClient.BalanceResult result =
                callWithAccount(userId, false, true, billing::balance);
        cacheBalance(userId, result, now);
        return result;
    }

    /**
     * 建充值单。
     *
     * <p>{@code idempotencyKey} <b>必须由客户端传入</b>（发起前落盘，扛得住 App 被杀），
     * 缺失即报错——服务端现生成等于没有幂等键，弱网重试会在官网库里留下一串各自绑着独立
     * 二维码的悬挂 pending 单，而官网<b>没有</b>针对充值 pending 单的过期回收任务。
     *
     * <p>这是<b>唯一</b>允许在官网建号的入口（{@code create=true}）：用户显式发起充值，
     * 建号是这个动作的应有之义。
     *
     * <p><b>总开关先行</b>（复审 N1）：{@code mobile.billing.recharge-enabled} 关时（默认）
     * 第一行就抛 DISABLED，参数校验、身份解析、上游请求一律不发生。「本期四端还没有充值界面」
     * 只是没人调，不是护栏——这个端点是活的。
     */
    public MobileBillingClient.RechargeOrder createRecharge(Long userId, Long amountCents, String idempotencyKey) {
        requireRechargeEnabled();
        if (amountCents == null || amountCents <= 0) {
            throw new IllegalArgumentException(LangText.of(
                    "充值金额必须大于 0", "Top-up amount must be greater than 0"));
        }
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(LangText.of(
                    "缺少或非法的 idempotencyKey，请升级客户端后重试",
                    "Missing or invalid idempotencyKey; please update the app and try again"));
        }
        return callWithAccount(userId, true, true,
                accountId -> billing.createRecharge(accountId, amountCents, key));
    }

    /**
     * 查充值单状态。到账即作废余额缓存，让下一次 balance 打真源。
     *
     * <p>与下单同一把总开关（复审 N1）：功能关着时查单也没有意义，且下单被挡住时不应该还留着
     * 一个能拿单号打上游的口子。
     *
     * <p>{@code create=false}（查单不是建号的理由），且<b>不做绑定自愈</b>：这条路的 404
     * 既可能是"账户没了"也可能是"单号不属于你"，分不出来就不要动绑定行——真出现账户被注销，
     * 用户读一次余额就会自愈。
     */
    public MobileBillingClient.RechargeStatus queryRecharge(Long userId, String outTradeNo) {
        requireRechargeEnabled();
        String no = outTradeNo == null ? "" : outTradeNo.trim();
        if (!OUT_TRADE_NO.matcher(no).matches()) {
            throw new IllegalArgumentException(LangText.of(
                    "缺少或非法的 outTradeNo", "Missing or invalid outTradeNo"));
        }
        MobileBillingClient.RechargeStatus status = callWithAccount(userId, false, false,
                accountId -> billing.queryRecharge(accountId, no));
        if (status.paid()) {
            balanceCache.remove(userId);
        }
        return status;
    }

    // ==================== 身份解析 ====================

    /**
     * 取该用户的官网 accountId（只查不建）。对外保留这个签名给旁路调用；
     * 建号的那条通路只从 {@link #createRecharge} 走。
     */
    public String requireAccountId(Long userId) {
        return requireAccountId(userId, false);
    }

    /**
     * 取该用户的官网 accountId：已绑定就用绑定，未绑定就拿<b>服务端 User 实体上已验证的</b>
     * 手机号/邮箱向官网 resolve 换一个并写入 {@code account_binding}。
     *
     * <p>审核账号先于一切被挡掉：即便它不知怎么已经有了绑定，也不许走充值。
     *
     * @param create 是否允许官网按该身份建号，见 {@link MobileBillingClient#resolveAccountId}
     */
    String requireAccountId(Long userId, boolean create) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of(
                        "账号不存在", "Account not found")));
        requireNotReviewAccount(user);

        Optional<AccountBinding> existing = accountBindingRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get().getExternalAccountId();
        }
        return bindFromVerifiedIdentity(user, create);
    }

    /**
     * 解析 accountId 后执行一次上游调用；上游回 NOT_FOUND 且这次用的是<b>既有绑定</b>时，
     * 清掉那行绑定重解析一次（绑定自愈）。
     *
     * <p>为什么需要自愈：{@link #requireAccountId} 命中既有绑定就直接返回，官网那侧账户被
     * 注销之后这行绑定永远指向一个不存在的账户，用户此后<b>永久</b>只能看到「未找到对应的
     * 统一账户」，App 里没有任何自救路径。{@code AwdkLoginService.resolveUser} 对
     * 「绑定指向的用户已被删除」早有同款自愈分支，这里照抄它的思路，只是方向相反
     * （那边是本地用户没了，这边是官网账户没了）。
     *
     * <p>只重试<b>一次</b>：重解析回来的若还是同一个 accountId（官网账户其实还在，404 来自
     * 别的原因），第二次照样失败就如实报错，不做无限循环。
     *
     * <p>删绑定会顺带影响 {@code MobileTransferService.tryRefund}（它按 userId 找绑定退款）：
     * 但走到这里的前提是官网侧那个账户已经不存在，退款本来就退不回去，而「永久卡死」是更坏的结果。
     */
    private <T> T callWithAccount(Long userId, boolean create, boolean healStaleBinding,
                                  Function<String, T> call) {
        boolean fromExistingBinding = accountBindingRepository.findByUserId(userId).isPresent();
        String accountId = requireAccountId(userId, create);
        try {
            return call.apply(accountId);
        } catch (MobileBillingClient.MobileBillingException e) {
            boolean stale = healStaleBinding
                    && fromExistingBinding
                    && e.getKind() == MobileBillingKind.NOT_FOUND
                    && dropStaleBinding(userId, accountId);
            if (!stale) {
                throw translate(e);
            }
            log.warn("绑定指向的统一账户在官网已不存在，清掉绑定并重新解析：userId={}", userId);
            String refreshed = requireAccountId(userId, create);
            try {
                return call.apply(refreshed);
            } catch (MobileBillingClient.MobileBillingException retry) {
                throw translate(retry);
            }
        }
    }

    /** 删掉这行绑定；只在它仍然指向我们刚用过的那个 accountId 时删（避免误删并发新建的行）。 */
    private boolean dropStaleBinding(Long userId, String accountId) {
        Optional<AccountBinding> row = accountBindingRepository.findByUserId(userId);
        if (row.isEmpty() || !accountId.equals(row.get().getExternalAccountId())) {
            return false;
        }
        accountBindingRepository.delete(row.get());
        return true;
    }

    /**
     * 充值总开关（复审 N1）。关时下单与查单一律 {@link MobileBillingKind#DISABLED}，
     * <b>在到达 {@code create=true} 之前短路</b>，不解析身份也不发任何上游请求。
     *
     * <p>为什么需要它：{@code POST /api/mobile/billing/recharge} 是全站唯一走
     * {@code create=true} 的调用方，随本期一起上线且是活的——任何持有有效
     * {@code X-Session-Id} 的人直接打它，就会在官网建出一行含明文手机号的真账户并发注册赠额。
     * 而 {@code AccountDeletionService} 依旧只删 Java 侧本地表、不通知官网，官网内部口也还
     * 没有 delete action，于是 App 自己建的账号 App 内没有任何路径能删掉——就是复审 C1 要堵的
     * App Store 5.1.1(v) 场景，只是触发点从「打开设置页」搬到了「直接打这个端点」。
     * 本期四端都没有充值界面，「没人调」是社会性约束而不是服务端护栏，所以要有这一行。
     *
     * <p><b>什么时候才允许打开</b>：等 dev-board#434（官网账户注销传导）落地——注销时能把删除
     * 传导到官网、官网内部口有 delete action 之后，把
     * {@code mobile.billing.recharge-enabled}（env {@code MOBILE_BILLING_RECHARGE_ENABLED}）
     * 置 true。在那之前打开等于把 5.1.1(v) 重新放出来。
     */
    private void requireRechargeEnabled() {
        if (!rechargeEnabled) {
            throw new MobileBillingFailureException(MobileBillingKind.DISABLED, LangText.of(
                    "此服务器未开通统一账户充值",
                    "Unified account top-up is not enabled on this server"));
        }
    }

    /**
     * App 审核专用账号一律拒绝。
     *
     * <p>它是一条认证旁路上的空账号（{@link ReviewAccountGate}，固定验证码写在审核备注里给
     * 外部人看），放它去 resolve 等于按审核员的手机号/邮箱在官网建出一个真账户，
     * 之后那把公开的 6 位码就成了进那个真账户的钥匙。
     */
    private void requireNotReviewAccount(User user) {
        if (reviewAccount.matches(user.getPhone()) || reviewAccount.matches(user.getVerifiedEmail())) {
            log.warn("审核演示账号请求统一账户功能，已拒绝：userId={}", user.getId());
            throw new MobileBillingFailureException(MobileBillingKind.REVIEW_ACCOUNT, LangText.of(
                    "审核演示账号不支持余额与充值",
                    "Balance and top-up are not available for the review demo account"));
        }
    }

    /**
     * 未绑定：用已验证身份 resolve 并落绑定。手机号优先（大陆主路径），其次已验证邮箱。
     *
     * <p><b>标识回退</b>（复审）：第一版无条件 {@code phone != null ? null : email}，于是
     * 「同时有手机号和已验证邮箱」的用户在国际站被硬拒——官网对不匹配站点能力的标识回
     * {@code 400 phone_not_supported_on_site}，而那条路没有第二次机会。现在按顺序试，
     * <b>只有 REJECTED 才换下一个标识</b>：那是官网在拒绝这个"标识"本身（站点不把手机号
     * 当账号、号码/邮箱形态不合法），这个标识从来就用不了，换一个不构成"换了个人"。
     * <b>NOT_FOUND 绝不回退</b>——那是官网权威地回答"按这个身份没有账户"，回退过去等于把
     * 用户悄悄关联到另一个官网账户上。
     */
    private String bindFromVerifiedIdentity(User user, boolean create) {
        String phone = trimToNull(user.getPhone());
        // 资料字段 email 不唯一也未必验过，只认 verifiedEmail
        String email = trimToNull(user.getVerifiedEmail());
        if (phone == null && email == null) {
            throw new MobileBillingFailureException(MobileBillingKind.NOT_CONNECTED, LangText.of(
                    "该账号还没有已验证的手机号或邮箱，无法关联统一账户",
                    "This account has no verified phone or email yet, so it cannot be linked to a unified account"));
        }

        // {phone, email}：恰好一个非 null，顺序即优先级
        List<String[]> attempts = new ArrayList<>(2);
        if (phone != null) attempts.add(new String[]{phone, null});
        if (email != null) attempts.add(new String[]{null, email});

        String accountId = null;
        for (int i = 0; i < attempts.size(); i++) {
            try {
                accountId = billing.resolveAccountId(attempts.get(i)[0], attempts.get(i)[1], create);
                break;
            } catch (MobileBillingClient.MobileBillingException e) {
                if (e.getKind() == MobileBillingKind.REJECTED && i + 1 < attempts.size()) {
                    log.info("统一账户 resolve 拒绝了第 {} 个标识，换下一个再试：userId={}, error={}",
                            i + 1, user.getId(), e.getMachineError());
                    continue;
                }
                throw resolveFailure(e);
            }
        }

        if (!StringUtils.hasText(accountId)) {
            // 200 但没给 accountId：上游异常，不是"这个人没有账户"（红线 7）
            log.warn("官网 resolve 未返回 accountId：userId={}", user.getId());
            throw new MobileBillingFailureException(MobileBillingKind.UNAVAILABLE, LangText.of(
                    "账户关联失败，请稍后重试", "Failed to link the account, please try again later"));
        }
        return saveBinding(user.getId(), accountId.trim());
    }

    /**
     * resolve 的失败翻译：官网回"按这个身份查无账户"（{@code create=false} 时的带 body 404）
     * 表示<b>这个登录账号还没有统一账户</b>，那是 NOT_CONNECTED 而不是 NOT_FOUND——
     * NOT_FOUND 的语义留给"绑定指向的账户没了"。其余原样透传。
     */
    private MobileBillingFailureException resolveFailure(MobileBillingClient.MobileBillingException e) {
        if (e.getKind() == MobileBillingKind.NOT_FOUND) {
            return new MobileBillingFailureException(MobileBillingKind.NOT_CONNECTED, LangText.of(
                    "该账号还没有关联的统一账户",
                    "This account is not linked to a unified account yet"));
        }
        return translate(e);
    }

    /**
     * 落绑定。{@code account_binding} 对 external_account_id 有唯一约束：
     * 已被<b>别的</b> userId 绑走时直接拒绝，绝不改绑——改绑就是把充值记到另一个人头上。
     *
     * <p>本方法刻意不带 {@code @Transactional}：撞唯一约束后要能在同一次调用里读回对方
     * 已提交的行，外层若有事务会被标 rollback-only（教训见 mobile-sync.md 地雷 6）。
     */
    private String saveBinding(Long userId, String accountId) {
        Optional<AccountBinding> owner = accountBindingRepository.findByExternalAccountId(accountId);
        if (owner.isPresent()) {
            if (userId.equals(owner.get().getUserId())) {
                return accountId;
            }
            log.warn("统一账户已绑定到另一个用户，拒绝改绑：userId={} 已绑 userId={}",
                    userId, owner.get().getUserId());
            throw new MobileBillingFailureException(MobileBillingKind.REJECTED, LangText.of(
                    "该统一账户已与另一个登录账号关联，请用原账号登录后充值",
                    "This unified account is already linked to another login; please sign in with that account"));
        }
        AccountBinding row = new AccountBinding();
        row.setUserId(userId);
        row.setExternalAccountId(accountId);
        row.setCreatedAt(LocalDateTime.now());
        try {
            accountBindingRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            // 并发首调：另一个请求已写入，读回它的结果即可
            return accountBindingRepository.findByUserId(userId)
                    .map(AccountBinding::getExternalAccountId)
                    .orElseThrow(() -> new MobileBillingFailureException(MobileBillingKind.UNAVAILABLE,
                            LangText.of("账户关联失败，请稍后重试",
                                    "Failed to link the account, please try again later")));
        }
        log.info("手机端统一账户绑定建立：userId={}", userId);
        return accountId;
    }

    // ==================== 内部 ====================

    private void cacheBalance(Long userId, MobileBillingClient.BalanceResult value, long nowMillis) {
        if (balanceCache.size() >= MAX_CACHE_ENTRIES) {
            balanceCache.entrySet().removeIf(e -> !e.getValue().fresh(nowMillis));
        }
        balanceCache.put(userId, new CachedBalance(value, nowMillis + BALANCE_TTL.toMillis()));
    }

    /** 测试与"换账号"场景用：作废某个用户的余额缓存。 */
    void invalidateBalance(Long userId) {
        balanceCache.remove(userId);
    }

    /**
     * 换壳成 {@link MobileBillingFailureException}（全局处理器 →
     * HTTP 200 + {@code {code:1, kind, outTradeNo?, message}}）。
     *
     * <p><b>kind 与 outTradeNo 必须原样带过去</b>（复审 C2/C4）。第一版这里只留 message，
     * 把 Kind 和官网 409 一起回的 outTradeNo 全丢了——用户刚付过钱却被告知"充值请求被拒绝"，
     * 且再也拿不到单号去查单；三端则只能去猜中文串。
     */
    private MobileBillingFailureException translate(MobileBillingClient.MobileBillingException e) {
        return new MobileBillingFailureException(e.getKind(), e.getMessage(), e.getOutTradeNo());
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
