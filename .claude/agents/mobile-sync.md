---
name: mobile-sync
description: 手机端同步领域。任务涉及手机端项目目录镜像、现场影像云中转（/api/mobile/*）、桌面侧 MobileRelayClientService、桥接认领手机号、aiworkdeck_mobile 仓的 iOS/小程序客户端时，先读本文档再动代码。
---

# 手机端同步（项目目录镜像 + 现场影像云中转）

手机端（独立仓 `1-3 aiworkdeck_mobile`：iOS Swift + 微信小程序）拍现场影像，归档到
桌面端项目的「现场影像/YYYY-MM-DD/」。**桌面端是项目的唯一权威源**，云端只做两件事：
目录镜像（手机「选择项目」的数据源）与影像中转区（ACK 即删 + 7 天 TTL 兜底）。
权威 spec：`aiworkdeck_mobile/docs/specs/2026-08-20-project-sync-relay.md`（根因复盘见
dev-board#30）；更早的产品设计 `2026-08-17-mobile-clients-design.md`。

## 关键文件

- `service/mobile/MobileRelayStoreService.java` — 云端（server 侧）：目录按 (userId,
  deviceId) 整批替换、影像入库（幂等键 userId+clientMediaId）、ACK（置 deliveredAt +
  **立即删 blob**、行保留供 status）、每日 TTL 清理。blob 落
  `{storage.local.root-path}/mobile-relay/{userId}/{clientMediaId}`。
- `controller/MobileRelayController.java` — `/api/mobile/*` 全组端点。鉴权一律
  `X-Session-Id`：手机端带登录会话，桌面端带 awdt_ 设备令牌（`AuthController.
  getUserIdFromSession` 两种都解析）。响应风格同 `/api/projects/my`（裸数组）。
- `service/mobile/MobileRelayClientService.java` — 桌面侧（local-mode 专属）：
  用本机 awdk_ 到云端换 awdt_（存 `~/.aiworkdeck/mobile-relay.json`，0600，含
  deviceId 与账户指纹）、每 10 分钟推项目目录（清单哈希不变则跳过）、每 60 秒
  轮询取件落盘 + ACK。
- `model/entity/MobileProjectDir.java` / `MobileMediaInbox.java` + 对应 repository。
- `service/UserService.claimPhoneFromWebsite` + `AwdkLoginService.login` 的认领调用 —
  桥接时把官网账户的手机号认领到桥接用户名下（占用者转移、已有异号不覆盖、永不抛出），
  使手机端 sms-login 的 `findOrCreateByPhone` 解析到同一账号。官网 `/api/account/me`
  的 `phone` 字段随官网 PR#87 上线。

## 核心契约

- 目录条目 = `{deviceId, key, name}`；`key` 是**那台桌面机本地库的项目 id**，跨机同号
  不同物，任何消费方必须连 deviceId 一起用。
- 影像幂等键 = (userId, clientMediaId)，clientMediaId 只收 UUID 形态（路径穿越围栏）。
- 删除由 ACK 触发，TTL 只是兜底——两个机制不能混。
- 桌面落盘文件名 = 原名 + clientMediaId 前 8 位（`landedFileName`）：跨轮重试的幂等锚点，
  「同名已在 → 只补 ACK」。
- 客户端换账号守卫：state 里的账户指纹与 `AccountService.accountFingerprintOrNull()`
  不一致即作废令牌重桥接（平台 AI key 在 PR#334 栽过同形状的坑）。

## 已知地雷

1. 目录整批替换在同一事务里 delete + insert 同键：`deleteByUserIdAndDeviceId` 后必须
   `flush()`，否则 Hibernate 动作队列把 INSERT 排在 DELETE 前撞唯一约束。
2. iOS v1（TestFlight 存量构建）用的还是 `/api/auth/sms-login` + `/api/projects/my` +
   `/api/projects/{id}/files/file` 旧链路——认领上线后登录能落到正确账号，但项目列表
   要等 iOS 切到 `/api/mobile/projects` 的新构建。
3. 项目在桌面端被删后，指向它的中转件**留置不 ACK**（云端 TTL 兜底），不要改成 ACK——
   那等于把用户拍的证据静默删掉。
4. 云端/团队服务器绝不能跑客户端：双闸 = `security.local-mode` + 账户 Key 在场。
5. **`landAndAck` 必须字节先落盘、元数据后落库**（2026-08-21 稳定性审计修复）：
   `ProjectFileService.createFile` 自带 `@Transactional`、本类没有事务包裹，一旦返回
   即已提交；旧实现先 `createFile`（建库 + `createFromTemplate` 物化模板文件）再
   `storage.save(...)` 写真实字节，`save` 抛 IOException（网络中断/磁盘满）时行已落库、
   ACK 没发，下一轮的幂等判据只按"同名文件已在数据库"判断、误判成已完成直接补 ACK——
   服务端随之删除中转区原件，现场影像永久丢失却显示"已送达"。修复后把
   `storage.save` 挪到 `createFile` 之前、显式传入按 `MEDIA_ROOT_FOLDER/日期/落盘文件名`
   算出的确定性存储路径（不再依赖 `createFile` 自动推导），落库这一步失败或落盘失败
   都不会产生"行已落库但字节没写对"的空壳。新增任何"先建库拿到路径、再写字节"的
   两段式落地路径都要检查这条顺序。护栏 `MobileRelayClientHttpTest.
   pollInboxFailedSaveLeavesNoOrphanRowAndRedownloadsNextRound`。
6. **`storeMedia` 的先查后插之间没有锁**（同批修复）：`MobileRelayStoreService.storeMedia`
   查 `findByUserIdAndClientMediaId` 后直接 `save`，中间没有锁也没有 upsert；并发重传
   （弱网重试）落败的一方会撞 `(user_id, client_media_id)` 唯一约束抛
   `DataIntegrityViolationException`，这条异常在 mobile 包与 `GlobalExceptionHandler`
   都没有专项处理，落到通用处理器变成 `{"code":1,"message":"服务器内部错误"}`——与方法
   注释里"幂等：弱网重传都不产生重复件"正相反。修复照抄
   `ProjectProfileService.saveUserField` 的 `self`-代理 + `REQUIRES_NEW` 重试模式（同类
   互相调用不经 Spring 代理，`@Transactional` 会被静默绕过；撞约束把当前事务标记
   rollback-only，同事务内 catch 后补救一样会在方法出口抛
   `UnexpectedRollbackException`，必须落在全新事务里重试）：`storeMedia` 现在不带
   `@Transactional`，捕获 `DataIntegrityViolationException | UnexpectedRollbackException`
   后经 `self.storeMediaTx(...)` 重试一次，重试时一定能查到对方已提交的记录。
   手工 `new MobileRelayStoreService(...)` 的测试要记得 `service.self = service;`
   （`MobileRelayStoreServiceTest`/`MobileRelayStoreServiceConcurrentStoreTest` 已接）。
7. **目录条数超过 `MAX_DIR_ENTRIES`（1000）不再整批拒绝**（尽调模块 P3 稳定性余项 #5，
   dev-board#100，与 P0 修 `LocalProjectService.MAX_IMPORT_ENTRIES` 同一口径：截断到
   上限 + 明确报告，不静默丢）——旧实现 `replaceDirectory` 超限直接抛
   `IllegalArgumentException`，桌面端 `pushDirectory` 从不本地裁剪清单、每 10 分钟原样
   重推同一份超限清单，结果是**整批**推送失败、一条项目都进不了库，且失败只在桌面日志
   留一句 `log.warn`（律师看不到），此后永远同样失败、永远无声。现在
   `replaceDirectory` 返回 `DirectoryReplaceResult(storedCount, totalCount, truncated)`：
   超限时截断到前 `MAX_DIR_ENTRIES` 条（客户端按 `findByUserIdOrderByCreatedAtDesc` 传
   来的顺序，即保留最新的那些）正常入库，不再抛异常；`truncated=true` 时额外
   `log.warn` 一次（服务端侧）。控制器 `PUT /api/mobile/projects` 响应体新增
   `totalCount`/`truncated` 两个字段（`count` 语义也从"请求条数"改成"实际入库条数"，
   未截断时两者相等，不影响既有断言）。桌面端 `MobileRelayClientService.pushDirectory`
   读这两个字段：`truncated=true` 时改发一条点名总数与已同步数的 WARN（不再是普通的
   "已推送"INFO），数字全部取服务端口径（`totalCount`/`count`），不与本地 `arr.size()`
   混用——服务端收到并落库的条数才是"其实同步了多少"的真相。护栏
   `MobileRelayStoreServiceTest.directoryOverLimitIsTruncatedNotRejected`（服务端截断）、
   `MobileRelayClientHttpTest.pushDirectoryTruncationIsLoudlyWarned`（客户端 WARN 日志，
   Logback `ListAppender` 断言，写法同 `AuthControllerGetUsernameLoggingTest`）。

## 排查「手机端一个项目都读不到」的顺序（dev-board#75 实测路径）

空数组是**合法响应**，没有报错也没有 4010，所以必须按下面的顺序把「哪一环是空的」逐段夹出来：

1. 桌面端有没有桥接：`~/.aiworkdeck/mobile-relay.json` 在不在（不在 = `active()` 没过闸，
   多半是没连账户，`enabled && local-mode && currentKeyOrNull() != null`）。
2. 云端目录镜像有没有：拿那个文件里的 `awdt_` 打
   `curl -H "X-Session-Id: awdt_…" https://addin.aiworkdeck.com/api/mobile/projects`。
   **有数组** = 桌面端推送这一段是好的，问题在手机侧账号。
3. 桥接账号是谁、有没有认领到手机号：同一个 `awdt_` 打 `/api/auth/me`，看 `phoneMasked`。
4. 手机端登录的是不是同一个账号——**这一步是历史坑的高发区**，见下。

## 已知地雷（续）

5. **手机号转移不动会话**：`claimPhoneFromWebsite` 把号码从旧账号 A 转到桥接账号 B 时，
   A 名下的登录会话**仍然有效**。手机 App 手里那张 A 的会话会继续用下去，而目录镜像挂在 B
   名下，A 名下空空如也 → 返回合法空数组 → 用户看到「一个项目都读不到」，
   **且反复重进也一样**（会话不到期就永远不会自愈）。
   现在转移时会调 `UserSessionService.revokeAllForUser(A)` 逼手机端重新登录，
   短信验证会把它落到归一后的 B 上。
   **已经踩了的存量用户**（转移发生在这个修复之前）修不回来，只能在手机端手动退出登录再登一次。
   回归用例 `PhoneClaimSessionRevocationTest`。

## 验证

- `mvn test -Dtest='MobileRelay*Test,AwdkLoginServiceTest'`（JDK 21）。
- 云端冒烟：`curl https://addin.aiworkdeck.com/api/mobile/projects` 无凭据应 401。
- iOS 侧改动跑 `aiworkdeck_mobile` 仓的构建 + TestFlight 通道（fastlane）。
