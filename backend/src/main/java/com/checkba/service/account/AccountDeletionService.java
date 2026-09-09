// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.repository.MobileDeviceStateRepository;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileProjectDirRepository;
import com.checkba.repository.MobileTransferRequestRepository;
import com.checkba.repository.UserRepository;
import com.checkba.repository.UserSessionRepository;
import com.checkba.service.LangText;
import com.checkba.service.mobile.MobileBillingClient;
import com.checkba.service.mobile.MobileBillingFailureException;
import com.checkba.service.mobile.MobileBillingKind;
import com.checkba.service.mobile.MobileRelayBlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 注销账号：把这个用户在云端的东西一次删干净。
 *
 * <p><b>为什么必须有。</b>App Store 审核指南 5.1.1(v)：支持注册的 App 必须**在 App 内**
 * 提供删除账号。手机端验证码登录即建号，所以这条对我们是硬要求
 * （2026-09-02 两端因 Guideline 2.1 被退回时点名了这一条）。
 *
 * <p><b>删的范围</b>——手机端用户在云端占的全部：
 * <ul>
 *   <li><b>官网侧的统一账户</b>（有 {@code account_binding} 时先传导，见下）</li>
 *   <li>中转区影像：先删 blob 再删行。blob 删不掉只 warn 不中断，剩下的由对象存储
 *       生命周期兜底——为了一个删不掉的文件把整个注销卡住，对用户是更坏的结果。</li>
 *   <li>项目目录镜像、设备心跳、传输请求</li>
 *   <li>会话（删完当场失效）、账号绑定、设备令牌</li>
 *   <li>最后删 app_users 行本身</li>
 * </ul>
 *
 * <p><b>注销要传导到官网</b>（dev-board#434）：充值路径会用已验证手机号在官网
 * {@code resolve(create=true)} 建出一行含明文手机号的真账户。只删 Java 侧本地表的话，
 * <b>App 自己建的账号 App 内没有任何路径能删掉</b>，直接撞 App Store 5.1.1(v)
 * 与个人信息保护法的删除权。所以有 {@code account_binding} 时先调官网内部口
 * {@code delete-account}，<b>再</b>删本地：
 * <ul>
 *   <li>官网删成功、或官网本来就查无此账户 → 继续删本地。</li>
 *   <li>官网明确拒绝（余额可退等）→ 抛可读业务错误，<b>本地一行都不删</b>，
 *       把官网给的原因原样告诉用户。</li>
 *   <li>官网不可达 / 5xx / 本机没配 {@code mobile.billing.*} → 同样中止，
 *       让用户稍后再试。<b>宁可让注销失败一次，也不能留下官网侧的孤儿账户</b>——
 *       本地删掉之后就再没有任何东西记得那个 accountId 了。</li>
 * </ul>
 * 没有 {@code account_binding} 的用户（从没碰过充值/余额，也包括整个服务器没配统一账户的
 * 部署）不受影响：一次上游请求都不发，照常删。
 *
 * <p><b>不碰手机本地的影像。</b>这是取证工具，现场不可复现；把用户手机上的原图一并
 * 销毁不是「清理」而是毁证。注销只清云端，本地留在设备上，由用户自己决定删不删
 * （客户端的确认弹窗会写明这一点）。
 *
 * <p>整个过程在一个事务里；blob 删除是外部副作用，放在事务内先做——失败只 warn，
 * 不会因为它回滚数据库。
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final MobileMediaInboxRepository inboxRepository;
    private final MobileProjectDirRepository dirRepository;
    private final MobileDeviceStateRepository deviceStateRepository;
    private final MobileTransferRequestRepository transferRepository;
    private final AccountBindingRepository bindingRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final MobileRelayBlobStore blobStore;
    /** 官网统一账户的内部记账口，注销传导用（dev-board#434）。 */
    private final MobileBillingClient billing;

    public AccountDeletionService(UserRepository userRepository,
                                  UserSessionRepository sessionRepository,
                                  MobileMediaInboxRepository inboxRepository,
                                  MobileProjectDirRepository dirRepository,
                                  MobileDeviceStateRepository deviceStateRepository,
                                  MobileTransferRequestRepository transferRepository,
                                  AccountBindingRepository bindingRepository,
                                  DeviceTokenRepository deviceTokenRepository,
                                  MobileRelayBlobStore blobStore,
                                  MobileBillingClient billing) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.inboxRepository = inboxRepository;
        this.dirRepository = dirRepository;
        this.deviceStateRepository = deviceStateRepository;
        this.transferRepository = transferRepository;
        this.bindingRepository = bindingRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.blobStore = blobStore;
        this.billing = billing;
    }

    /** 删除结果，仅用于日志与回包里的计数（不回具体内容）。 */
    public record Result(int media, long projects, long devices, long transfers, long sessions) {}

    @Transactional
    public Result deleteAccount(Long userId) {
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("账号不存在或已注销");
        }

        // 官网侧先删（dev-board#434）：失败即中止，本地一行都不动
        propagateToUnifiedAccount(userId);

        List<MobileMediaInbox> items = inboxRepository.findByUserId(userId);
        for (MobileMediaInbox item : items) {
            if (item.getStoragePath() != null) {
                blobStore.deleteQuietly(item.getStoragePath());
            }
        }
        long media = inboxRepository.deleteByUserId(userId);
        long projects = dirRepository.deleteByUserId(userId);
        long devices = deviceStateRepository.deleteByUserId(userId);
        long transfers = transferRepository.deleteByUserId(userId);
        long sessions = sessionRepository.deleteByUserId(userId);
        bindingRepository.deleteByUserId(userId);
        deviceTokenRepository.deleteByUserId(userId);
        userRepository.deleteById(userId);

        log.info("账号已注销 userId={}：影像 {}、项目目录 {}、设备 {}、传输请求 {}、会话 {}",
                userId, media, projects, devices, transfers, sessions);
        return new Result((int) media, projects, devices, transfers, sessions);
    }

    /**
     * 把删除传导到官网（dev-board#434）。没有绑定就什么都不做——那种用户在官网侧根本没有账户，
     * 发一次请求只会在没配统一账户的部署上把注销变成必然失败。
     *
     * <p>三条失败路径都<b>不删本地</b>，理由是同一条：本地的 {@code account_binding} 是
     * 「哪个 accountId 属于这个人」的唯一记录，删掉它之后官网那行含明文手机号的账户就再也
     * 没人认领得了。注销失败一次用户还能重试，孤儿账户没人能收拾。
     */
    private void propagateToUnifiedAccount(Long userId) {
        Optional<AccountBinding> binding = bindingRepository.findByUserId(userId);
        if (binding.isEmpty()) {
            return;
        }
        String accountId = binding.get().getExternalAccountId();

        MobileBillingClient.DeleteAccountResult result;
        try {
            result = billing.deleteAccount(accountId);
        } catch (MobileBillingClient.MobileBillingException e) {
            // DISABLED（本机没配 mobile.billing.*，但这个人偏偏有绑定）与 UNAVAILABLE 都归这里：
            // 都是「说不清官网那边到底怎么样」，一律不删本地
            log.warn("注销传导到官网失败，已中止本地注销：userId={}, kind={}", userId, e.getKind());
            throw new MobileBillingFailureException(MobileBillingKind.UNAVAILABLE, LangText.of(
                    "账户服务暂不可用，暂时无法注销，请稍后再试",
                    "Account service is temporarily unavailable; please try deleting your account later"));
        }

        if (!result.deleted()) {
            log.warn("官网拒绝删除统一账户，已中止本地注销：userId={}, blocker={}",
                    userId, result.blocker());
            String message = result.message() == null || result.message().isBlank()
                    ? LangText.of("统一账户暂时无法注销，请联系客服",
                            "Your unified account cannot be deleted right now; please contact support")
                    : result.message();
            throw new MobileBillingFailureException(MobileBillingKind.REJECTED, message);
        }
        log.info("统一账户已随注销一并删除：userId={}", userId);
    }
}
