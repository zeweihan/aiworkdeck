// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    private final com.checkba.service.team.TeamUsageSettings teamUsageSettings;
    private final com.checkba.service.team.TeamSettingsCache teamSettingsCache;
    private final com.checkba.service.team.TeamProjectNameNotice teamProjectNameNotice;
    private final com.checkba.service.SystemSettingService systemSettingService;
    private final AccountIdentitySync identitySync;

    /** 单机桌面版判别位。非 final：不进 {@code @RequiredArgsConstructor}，单测用 ReflectionTestUtils 设。 */
    @org.springframework.beans.factory.annotation.Value("${security.local-mode:false}")
    private boolean localMode;

    /**
     * 这台电脑上一次连接的官网账户 id（登录后置设计 §5.5，dev-board#1046）。
     *
     * <p>用 accountId 而不是 {@link AccountService#accountFingerprintOrNull()}：指纹是 Key 的摘要，
     * 而每次验证码登录官网都签发一把新 Key——同一个人重新登录一次指纹就变，会被误判成换了人。
     * accountId 是官网的稳定 id（公开头像地址里就有它），不是凭据。
     *
     * <p>落 {@code system_setting} 而不是 {@code ~/.aiworkdeck}：它回答的是「这个库里的本机项目
     * 上一次挂在谁名下」，与 {@code local.identity.selectedUserId} 同库同生死——还原一份旧库，
     * 记录跟着回到那个时点才对。断开账户时刻意不清，否则下次登录永远比不出「换没换人」。
     */
    public static final String KEY_LAST_ACCOUNT_ID = "account.lastAccountId";

    /**
     * 刚连上一个（可能是不同的）账户：旧账户的一切当场作废，再异步拉新账户的权益。
     *
     * @return 这次连上的是否是另一个账户（此前连过、且 accountId 不同）。一次性：
     *         记录随即改成新账户，同一账户再登录不再为 true。调用方据此在回包里带
     *         {@code previousAccountDiffers:true}，前端弹一次「本机项目属于这台电脑」的说明。
     */
    public boolean afterConnect() {
        invalidateAll();
        entitlementService.refreshAsync();
        return recordAccount();
    }

    private boolean recordAccount() {
        try {
            String current = accountService.currentAccountIdOrNull();
            if (current == null || current.isBlank()) {
                // 官网没给 accountId（老盘 / 契约漂移）：不猜，也不拿空值盖掉旧记录
                return false;
            }
            String previous = systemSettingService.get(KEY_LAST_ACCOUNT_ID, null);
            if (current.equals(previous)) {
                return false;
            }
            systemSettingService.set(KEY_LAST_ACCOUNT_ID, current);
            return previous != null && !previous.isBlank();
        } catch (RuntimeException e) {
            // 提示是顺手的事，为它把连接账户搞失败不划算
            log.warn("记录本机账户历史失败（不影响连接）: {}", e.toString());
            return false;
        }
    }

    /**
     * 刚断开账户。除作废缓存外，团队服务器上还要把 AI 供应商从平台通道摘下来
     * （否则界面显示平台通道正常选中、实际每条消息都报未连接账户）；单机版不摘，见方法体。
     *
     * @return 降级到的供应商名；单机版、或本来就不是平台通道时返回 null
     */
    public String afterDisconnect() {
        invalidateAll();
        // 本机行不再顶着上一个账户的名字与头像（§5.5）。账户记录不清，见 KEY_LAST_ACCOUNT_ID
        identitySync.resetToLocal();
        // 单机版不降级（登录后置，dev-board#1046）：「平台通道选中 + 未连接账户」是全新安装的常态，
        // 下一条 AI 消息由 4011 → 登录弹层承接。降到 OLLAMA 会让同一台机器出现两种默认值，
        // 而官方版界面没有 BYOK 入口，用户下一条消息会静默发给多半没装的本地模型。
        // 团队服务器保持原行为（那里没有登录弹层可承接）。
        if (localMode) {
            return null;
        }
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
        // 团队台账同理（dev-board#496）：「哪些天已经传过了」记的是「传给<b>那个</b>账户」，
        // 换了人必须从头传；「共享项目名」是上一个团队的设置，留着会让下一个团队的
        // 日聚合按旧团队的口径带上项目名
        teamUsageSettings.resetLedger();
        teamSettingsCache.clear();
        // 项目名上云的那一次确认同理（C4）：它是对着上一个账户所在团队的听众给的，
        // 换了人必须重新问一次，否则新团队的看板会直接冒出这台机器上的真实客户名
        teamProjectNameNotice.reset();
    }
}
