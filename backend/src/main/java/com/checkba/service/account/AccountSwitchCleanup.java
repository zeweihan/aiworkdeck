package com.checkba.service.account;

import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PlatformCreditsGate;
import com.checkba.service.ai.PlatformUsageAccountant;
import com.checkba.service.entitlement.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 换账户之后必须作废的那一堆机器级缓存，收成一处。
 *
 * <h3>为什么必须只有一份</h3>
 * 连接账户有<b>两个</b>入口：{@code AccountController.connect}（设置页）和
 * {@code LicenseController.activate}（解锁页粘 {@code awdk_}，一步到位）。
 * 清理动作原先只写在前者里，后者只调了 {@code entitlementService.refreshAsync()}——
 * 于是从解锁页换成另一个账号时，上一个账号的<b>平台 AI 密钥、已购权益、用量基线</b>三样
 * 全部原封不动留着：新账号没充值也能接着花上一个账号的 OpenRouter 额度，
 * 也继承了上一个账号买过的付费项。（2026-08 起同一道理再管一样：
 * {@code /api/account/balance} 的 profile/membership TTL 缓存，同样是账户级内容。）
 *
 * <p>而解锁页恰恰是主入口——用账户 Key 解锁的人走的就是那条路。
 * 这是本仓反复踩到的同一个形状：<b>同一道闸有两个入口时，动作必须只有一处定义</b>，
 * 否则漏的总是那个没人天天看的入口。新增第三条连接账户的路径时接这里，别再抄一遍。
 *
 * <p>注意这不是唯一的防线，也不该是：平台密钥缓存本身还记着签发它的账户指纹
 * （{@link PlatformAiChannel} 的 {@code owner}），归属对不上就丢弃重取。
 * 「记得清缓存」是会忘的，「归属对不上就不认」不会——两层都要在。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountSwitchCleanup {

    private final AccountService accountService;
    private final EntitlementService entitlementService;
    private final PlatformAiChannel platformAiChannel;
    private final PlatformCreditsGate platformCreditsGate;
    private final PlatformUsageAccountant platformUsageAccountant;
    private final ChatModelFactory chatModelFactory;

    /** 刚连上一个（可能是不同的）账户：旧账户的一切当场作废，再异步拉新账户的权益。 */
    public void afterConnect() {
        invalidateAll();
        entitlementService.refreshAsync();
    }

    /**
     * 刚断开账户。除作废缓存外还要把 AI 供应商从平台通道摘下来，
     * 否则界面显示平台通道正常选中、实际每条消息都报未连接账户。
     *
     * @return 降级到的供应商名；本来就不是平台通道时返回 null
     */
    public String afterDisconnect() {
        invalidateAll();
        return chatModelFactory.demotePlatformProvider();
    }

    private void invalidateAll() {
        entitlementService.clearAccountCache();
        platformAiChannel.clearCache();
        platformCreditsGate.reset();
        platformUsageAccountant.resetBaseline();
        // /api/account/balance 的 profile/membership TTL 缓存也是账户级内容（dev-board#183/#184）：
        // 不清的话换一个没充值的新账号进来，顶栏会先展示上一个账号的余额/等级直到缓存自然过期
        accountService.clearBalanceCache();
    }
}
