package com.checkba.service.ai;

import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 平台通道的余额闸：<b>确知</b> Credits 为 0 时不让 AI 跑起来。
 *
 * <p>在这之前，「没充值就不能用」全靠两道间接闸：官网在 {@code POST /api/account/ai-key}
 * 对零余额账户回 409、以及 OpenRouter 侧 per-key 的 limit。两道都有各自的盲区——
 * 前者只在<b>取 key 那一刻</b>生效，本地已经缓存过 key 就再也不会问；
 * 后者管的是「这把 key 花了多少」，而不是「这个人还有没有钱」。
 * 于是零余额账户在本机拿着一把还没花完的 key 时是畅通的。这道闸补的就是那一段。
 *
 * <h3>三条判据（顺序不能换）</h3>
 * <ol>
 *   <li><b>只管机器级路径</b>（{@link PlatformAiChannel#usesMachineKey}）。per-user 路径的额度
 *       在官网签发 key 时就按人闸住了，且本端拿不到对方的 awdk_ Key，查也查不了。</li>
 *   <li><b>确知为 0 才拦</b>。官网 {@code GET /api/account/ai-usage} 在上游不可达时仍回 200 +
 *       真实 {@code creditsCents}；只有网络失败、端点缺失、字段缺失这三种「不知道」的情形，
 *       一律放行。查不到不等于没钱——反过来判会让人一断网就用不了
 *       （同 licensing-billing 地雷 6：权益失效不等于把人锁在外面）。</li>
 *   <li><b>首次同步、之后后台刷新</b>。全新的零余额账户第一条消息就必须被拦住，所以第一次
 *       不能异步；之后 60 秒内复用结果、过期后后台刷，不给每条消息加一次往返。</li>
 * </ol>
 *
 * <p>状态按<b>账户指纹</b>记，换账号自动作废，与 {@link PlatformAiChannel} 的密钥归属判据同源。
 */
@Service
@Slf4j
public class PlatformCreditsGate {

    /** 结果保鲜期。够短到充值后很快解闸，够长到不给每条消息加一次官网往返。 */
    private static final long FRESH_MS = 60_000L;

    /** 文案红线：不得含「登录」「未授权」「请先」——前端 api.js 用这三个子串判掉线并清会话。 */
    private static final String EMPTY_MESSAGE =
            "「AI WorkDeck 云端」的 Credits 余额为空，到官网账户页充值后即可继续使用";

    private final AccountService accountService;
    private final PlatformAiChannel channel;

    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "platform-credits-gate");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** null = 不知道（放行）；非 null = 官网明确给出的余额（分）。 */
    private volatile Long creditsCents;
    private volatile long checkedAt;
    /** 这份结果属于哪个账户；与当前账户对不上就当没查过。 */
    private volatile String checkedOwner;

    public PlatformCreditsGate(AccountService accountService, PlatformAiChannel channel) {
        this.accountService = accountService;
        this.channel = channel;
    }

    /**
     * 平台通道建模型之前调用。
     *
     * @throws AccountException CONFLICT——确知余额为 0
     */
    public void ensureCredits(Long userId) {
        if (!channel.usesMachineKey(userId)) return;
        // 未连接账户是另一条错误路径（PlatformAiChannel 会报 NOT_CONNECTED），这里不抢答
        String owner = accountService.accountFingerprintOrNull();
        if (owner == null) return;

        if (!owner.equals(checkedOwner) || checkedAt == 0L) {
            probe(owner);
        } else if (System.currentTimeMillis() - checkedAt > FRESH_MS) {
            refreshAsync(owner);
        }

        Long credits = creditsCents;
        if (owner.equals(checkedOwner) && credits != null && credits <= 0L) {
            throw new AccountException(AccountException.Kind.CONFLICT, EMPTY_MESSAGE);
        }
    }

    /** 连接/断开账户后清空，让下一条消息立刻重新判定（充值后不必等 60 秒）。 */
    public void reset() {
        creditsCents = null;
        checkedAt = 0L;
        checkedOwner = null;
    }

    private void refreshAsync(String owner) {
        if (!refreshing.compareAndSet(false, true)) return;
        refresher.submit(() -> {
            try {
                probe(owner);
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void probe(String owner) {
        Long value = null;
        try {
            Map<String, Object> quota = accountService.fetchAiUsage();
            Object credits = quota.get("creditsCents");
            // 字段缺失 = 官网还没升到 Credits 口径，按「不知道」处理
            value = credits instanceof Number n ? n.longValue() : null;
        } catch (Exception e) {
            // 不可达一律放行；这里刻意不保留上一次的 0，否则一次抖动就把刚充完值的人锁住
            log.debug("平台通道余额查询失败，本次放行: {}", e.getMessage());
        }
        creditsCents = value;
        checkedOwner = owner;
        checkedAt = System.currentTimeMillis();
    }
}
