package com.checkba.service.account;

import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.DeviceTokenRepository;
import com.checkba.repository.MobileDeviceStateRepository;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileProjectDirRepository;
import com.checkba.repository.MobileTransferRequestRepository;
import com.checkba.repository.UserRepository;
import com.checkba.repository.UserSessionRepository;
import com.checkba.service.mobile.MobileRelayBlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 注销账号：把这个用户在云端的东西一次删干净。
 *
 * <p><b>为什么必须有。</b>App Store 审核指南 5.1.1(v)：支持注册的 App 必须**在 App 内**
 * 提供删除账号。手机端验证码登录即建号，所以这条对我们是硬要求
 * （2026-09-02 两端因 Guideline 2.1 被退回时点名了这一条）。
 *
 * <p><b>删的范围</b>——手机端用户在云端占的全部：
 * <ul>
 *   <li>中转区影像：先删 blob 再删行。blob 删不掉只 warn 不中断，剩下的由对象存储
 *       生命周期兜底——为了一个删不掉的文件把整个注销卡住，对用户是更坏的结果。</li>
 *   <li>项目目录镜像、设备心跳、传输请求</li>
 *   <li>会话（删完当场失效）、账号绑定、设备令牌</li>
 *   <li>最后删 app_users 行本身</li>
 * </ul>
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

    public AccountDeletionService(UserRepository userRepository,
                                  UserSessionRepository sessionRepository,
                                  MobileMediaInboxRepository inboxRepository,
                                  MobileProjectDirRepository dirRepository,
                                  MobileDeviceStateRepository deviceStateRepository,
                                  MobileTransferRequestRepository transferRepository,
                                  AccountBindingRepository bindingRepository,
                                  DeviceTokenRepository deviceTokenRepository,
                                  MobileRelayBlobStore blobStore) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.inboxRepository = inboxRepository;
        this.dirRepository = dirRepository;
        this.deviceStateRepository = deviceStateRepository;
        this.transferRepository = transferRepository;
        this.bindingRepository = bindingRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.blobStore = blobStore;
    }

    /** 删除结果，仅用于日志与回包里的计数（不回具体内容）。 */
    public record Result(int media, long projects, long devices, long transfers, long sessions) {}

    @Transactional
    public Result deleteAccount(Long userId) {
        if (userId == null || userRepository.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("账号不存在或已注销");
        }

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
}
