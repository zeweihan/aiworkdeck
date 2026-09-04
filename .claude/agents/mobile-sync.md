---
name: mobile-sync
description: 手机端同步领域。任务涉及手机端项目目录镜像、现场影像云中转（/api/mobile/*）、桌面侧 MobileRelayClientService、桥接认领手机号、aiworkdeck_mobile 仓的 iOS/小程序客户端时，先读本文档再动代码。
---

# 手机端同步（项目目录镜像 + 现场影像/录音云中转）

手机端（独立仓 `1-3 aiworkdeck_mobile`：iOS Swift + 微信小程序）拍现场影像与录音，归档到
桌面端项目的「现场影像/YYYY-MM-DD/」（image/video）或「现场录音/YYYY-MM-DD/」（audio，
dev-board#228）。**桌面端是项目的唯一权威源**，云端只做两件事：
目录镜像（手机「选择项目」的数据源）与影像中转区（ACK 即删 + 30 天 TTL 兜底）。
权威 spec：`aiworkdeck_mobile/docs/specs/2026-08-20-project-sync-relay.md`（根因复盘见
dev-board#30）；更早的产品设计 `2026-08-17-mobile-clients-design.md`。

## 关键文件

- `service/mobile/MobileRelayStoreService.java` — 云端（server 侧）：目录按 (userId,
  deviceId) 整批替换、影像入库（幂等键 userId+clientMediaId）、ACK（置 deliveredAt +
  **立即删 blob**、行保留供 status）、每日 TTL 清理。blob 存取经
  `MobileRelayBlobStore` 接缝（dev-board#236）：本地实现落
  `{storage.local.root-path}/mobile-relay/{userId}/{clientMediaId}`（desktop/测试默认）；
  云后端配齐 `MOBILE_RELAY_OSS_*` 环境变量则走 OSS 私有桶（北京 `awd-mobile-relay`/
  国际站 `awd-mobile-relay-intl`，key = `mobile-relay/{userId}/{clientMediaId}`，
  桶生命周期 35 天兜底）。storagePath 存定位符（本地=绝对路径，OSS=object key），
  其「非空=占配额」的第二重身份不变；存量本地行按 `/` 前缀双读兼容。
  下载契约红线：`GET /inbox/{id}/content` 必须 2xx + `application/octet-stream` +
  裸字节，**不许 302 到签名 URL**——桌面端 `MobileRelayClientService` 不跟随重定向
  且硬校验 Content-Type。
- `controller/MobileRelayController.java` — `/api/mobile/*` 全组端点。鉴权一律
  `X-Session-Id`：手机端带登录会话，桌面端带 awdt_ 设备令牌（`AuthController.
  getUserIdFromSession` 两种都解析）。响应风格同 `/api/projects/my`（裸数组）。
- `service/mobile/MobileRelayClientService.java` — 桌面侧（local-mode 专属）：
  用本机 awdk_ 到云端换 awdt_（存 `~/.aiworkdeck/mobile-relay.json`，0600，含
  deviceId 与账户指纹）、每 10 分钟推项目目录（清单哈希不变则跳过）、每 60 秒
  轮询取件落盘 + ACK。
- `model/entity/MobileProjectDir.java` / `MobileMediaInbox.java` / `MobileDeviceState.java`
  （dev-board#250，设备心跳落点，每 (userId, deviceId) 一行记 lastSeenAt + nullable 的
  device_name——`PUT /projects` 的 touchDevice 顺带更新，目录行为 0 的设备在 listDevices
  里靠它出名字；表结构走 Hibernate `ddl-auto: update` 自动建，没有手写迁移）+ 对应
  repository。
- `service/UserService.claimPhoneFromWebsite` + `AwdkLoginService.login` 的认领调用 —
  桥接时把官网账户的手机号认领到桥接用户名下（占用者转移、已有异号不覆盖、永不抛出），
  使手机端 sms-login 的 `findOrCreateByPhone` 解析到同一账号。官网 `/api/account/me`
  的 `phone` 字段随官网 PR#87 上线。

## 核心契约

- 目录条目 = `{deviceId, key, name}`；`key` 是**那台桌面机本地库的项目 id**，跨机同号
  不同物，任何消费方必须连 deviceId 一起用。
- 影像幂等键 = (userId, clientMediaId)，clientMediaId 只收 UUID 形态（路径穿越围栏）。
- 删除由 ACK 触发，TTL（30 天，dev-board#226 从 7 天延长）只是兜底——两个机制不能混。
- `mediaType ∈ {image, video, audio}`（audio 自 dev-board#228）。桌面落盘根目录由它推导：
  audio → 「现场录音」，其余 → 「现场影像」；rootFolder 同时参与幂等判据与 storagePath
  拼接，改动必须两处同源（`MobileRelayClientService.landAndAck`）。
  **但 `mediaType` 只决定目录，绝不能当 `project_file.file_type` 落库**（dev-board#417，
  见地雷 9）：file_type 全仓语义是**文件扩展名**，落盘时一律走
  `MobileRelayClientService.fileTypeOf(landedName, mediaType)`（有扩展名取扩展名，
  没有才退回 mediaType）。
- **每用户 3GB 配额**（dev-board#226）：只计未投递 blob（storagePath 非空行）的 fileSize
  之和，ACK 即删 = 释放配额。检查在写盘前按声明大小做（幂等重传先于配额检查，不占新
  空间不得拒）；并发上传可略超上限（最多一件，接受的软度）。拒绝走
  IllegalArgumentException → HTTP 200 + `{code:1,message:"云端空间已满…"}`，恰是两端
  客户端能透传到界面的形状（非 2xx 的 body 会被客户端丢弃，别改成 413）。
- `GET /api/mobile/media/usage` → `{usedBytes, quotaBytes}`（裸对象）；
  `/media/status` 未投递件带 `expiresAt`（createdAt+TTL，ISO 字符串）供手机端做到期提醒。
- 桌面落盘文件名 = 原名 + clientMediaId 前 8 位（`landedFileName`）：跨轮重试的幂等锚点，
  「同名已在 → 只补 ACK」。
- 客户端换账号守卫：state 里的账户指纹与 `AccountService.accountFingerprintOrNull()`
  不一致即作废令牌重桥接（平台 AI key 在 PR#334 栽过同形状的坑）。
- **在线判定**（dev-board#250，`MobileRelayStoreService.ONLINE_WINDOW = 180s`）：桌面端
  `GET /inbox`（真心跳，60 秒轮询）与 `PUT /projects` 各调一次 `touchDevice`，180 秒内有
  心跳即在线。`GET /api/mobile/devices`（裸数组）给插件端账号级设备清单：目录行按
  deviceId 分组、deviceName 取组内第一个非空值、join 心跳表出 `online`，排序 online 优先。
  **有心跳但没有目录行的设备也出现**（projects 空数组，deviceName 取心跳行的 device_name、
  取不到给空串；插件端对空 optgroup 渲染一条 disabled 占位 option，i18n 键
  `remoteNoProjects`）——见地雷 8 的多实例顶目录形态。#251 跨设备文件传输的在线闸复用
  同一个 `isDeviceOnline`。

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
8. **多后端实例共享 relay 身份会互相顶目录**（2026-08-29 线上实测，userId=3 / 设备
   33766e71）：本机常态跑着 e2e/dev/优化者多个后端实例，凡不改 `user.home` 的实例都读写
   同一份 `~/.aiworkdeck/mobile-relay.json`——同一个 deviceId。测试实例本地库是空的，
   一次空清单 `PUT /projects` 就把真桌面端推过的目录整批顶成 0 行，设备随之从
   `listDevices` 消失（插件项目下拉只剩别的设备）。三重防线：
   （a）桌面端 `pushDirectory` 本地项目列表为空时不出站（log.info 跳过）；
   （b）服务端 `replaceDirectory` 收到空清单且该 (userId,deviceId) 现存目录行非空时跳过
   整批替换、保留现有行（语义权衡已裁决：真删光全部项目时目录短暂陈旧可接受，被测试
   实例清空不可接受；心跳照常 touch）；
   （c）`listDevices` 不再隐藏「有心跳但无目录行」的设备（projects 空数组 +
   心跳表 device_name）。deviceId 机器指纹轮换是另案，尚未做。护栏
   `MobileRelayStoreServiceTest.emptyDirectoryPushDoesNotWipeExistingRows` /
   `.touchDeviceStoresDeviceNameForHeartbeatOnlyDevices`、
   `MobileRelayClientHttpTest.pushDirectorySkipsWhenLocalProjectListEmpty`。
9. **`project_file.file_type` 是扩展名，不是 mediaType**（dev-board#417，2026-09-03 实测）：
   `landAndAck` 原来把 `mediaType`（image/video/audio）直接当 fileType 落库，而全仓
   （含 `ProjectFile` 的字段注释）对这一列只有一个语义——**文件扩展名**。后果不是报错，
   是**静默的"证据丢了"**：字节完好躺在项目目录里，用户在文件树里点开手机传来的 jpg
   只弹「无法打开文件：暂不支持打开此类型文件…文件类型：image」——前端
   `fileOpenTabs.isFileTypeSupported` 的白名单里是 jpg/png/mp4，没有 image/video/audio。
   同一个错还让 `FileTree.isAudioFile` 恒 false，资源管理器右键的「转写」
   （dev-board#228）在手机传来的录音上**永远不出现**，等于那张卡白做。
   现场取证：本机 H2 里 id=2248 的 `现场影像-20260902-191122-D160-d16044f3.jpg`
   file_type='image'，而 `file` 看字节是货真价实的 iPhone JPEG。
   同一个类里其余三条落地路径（`landDocumentAndAck`、传输 PUSH、
   `MobileTransferService.saveToProject`）本来就落扩展名，**只有 landAndAck 落错**——
   新增任何落盘路径都要照 `fileTypeOf(landedName, fallback)` 取值。
   **存量脏行只能靠启动期对账救**：影像早已 ACK、中转区 blob 早已删除，取件轮询再也
   不会碰它；本地目录项目的 `LocalProjectService` 重扫走 `createOrUpdateFile`，那个方法
   只更新 fileSize/updatedAt，**不改 fileType**，也救不回来。于是有
   `MediaFileTypeReconciler`（照 `OrphanPhoneSessionReconciler` 的成例做的
   `CommandLineRunner`，只在 local-mode 跑）：把 file_type ∈ {image,video,audio} 且名字
   带扩展名的文件行改回扩展名，幂等。护栏 `MobileRelayClientHttpTest.
   pollInboxStoresExtensionNotMediaTypeAsFileType` / `.pollInboxStoresAudioExtensionAsFileType`
   / `.pollInboxFallsBackToMediaTypeWhenNameHasNoExtension`、`MediaFileTypeReconcilerTest`、
   `ProjectFileRepositoryTreeSkeletonTest.findsFilesByFileTypeExcludingFoldersAndDeletedRows`
   （派生查询名真能被 Spring Data 解析，mock 证明不了这一点）。
   唯一还认 fileType="image" 的消费方是 `ContextAssemblerService.isVisionCandidate`，
   而它**只在文件名没有扩展名时**才看这一列——所以「有扩展名取扩展名、没有才退回
   mediaType」的兜底不能省。

## 插件归档双镜像（dev-board#297/#298/#299，spec：docs/superpowers/specs/2026-08-30-addin-project-binding-and-mirrors-design.md）

Office/WPS 插件里选中远程设备分组的桌面项目 = **归档绑定**（`AddinProjectLink`：
(userId, deviceId, projectKey) → 云端影子容器项目；`POST /api/projects/ensure-addin-link`
find-or-create，影子项目从 `/api/projects/my` 滤掉）。绑定后两条镜像流复用本领域的中转模式：

- **对话镜像**：云端 `AddinConvSyncOutbox`（每消息一行，刷新=删旧插新，30 天 TTL）→
  `GET /api/mobile/conversations/inbox?deviceId=` + `POST /api/mobile/conversations/ack`
  （鉴权/风格同 /api/mobile/*）→ 桌面 `pollConversationSync()`（挂在 pollInbox 的 **finally**，
  与 pollTransferCommands 同款；404 进程内钉死）。项目缺失的行**留置不 ACK**（同 media 地雷 3）；
  content 空白/坏 role 的行导入被拒但照样 ACK（永远导不进去，留着堵队列）。
- **文档镜像**：`mediaType='document'`（storeMediaTx 白名单第四值），走既有 media inbox
  （幂等键/配额 3GB 共池/ACK 即删/TTL 全复用）。桌面落盘**与其它类型语义相反**：
  「插件文档/<原名>」**固定路径覆盖**（无日期层、无 marker——路径唯一是覆盖语义的锚点，
  历史交给版本记录），`landDocumentAndAck`：字节先写同目录临时 key → `StorageService.move`
  原子顶替（本地 Files.move，同卷原子；接口新增 default 实现）→ `createOrUpdateFile`。
  写失败旧文件完好、不 ACK、下轮重试；同字节重放无害。**字节先落、库后动的红线不变**。
  插件端采集在 office-addin 领域（docSnapshot.js：Office getFileAsync(Compressed)/WPS
  FileSystem 探测链，拿不到只提示不硬凑）。

## 跨设备文件传输（dev-board#251，spec：docs/superpowers/specs/2026-08-28-cross-device-transfer.md）

- 文件：`MobileTransferService`/`MobileTransferController`（`/api/mobile/transfer/*`，鉴权同组）、
  `MobileTransferRequest`（unique(user_id, request_id)，requestId=UUID 围栏；**storagePath
  非空=占配额**，与 media 同第二重身份）、`TransferBillingClient` + Http 实现（POST 官网
  `/api/internal/transfer`，`X-Internal-Secret`；配置 `mobile.transfer.billing.base-url/secret`
  两个 env，未配=DISABLED 可读拒绝，绝不免费放行）。
- 两条链路：**拉取**（LIST PENDING→DONE 出清单；PULL 建行即扣费 PENDING→B 上传 STAGED→A
  save-to-project 落云项目「跨设备文件/日期/名+requestId前8位」DELIVERED，字节先落盘后
  createFile 的顺序红线同 landAndAck；LIST/PULL 建行要求 B 在线 180 秒窗口）；**投送**
  （PUSH 建行即扣费+从云项目文件复制入 blob STAGED→B 落盘「跨设备文件/日期/名-t<id>」
  +ack DELIVERED，B 可离线）。FAILED/EXPIRED/cancel 一律退款（幂等键 xferrf-requestId，
  失败留 refundedAt 空由每小时 TTL 清扫重试）；TTL：LIST 10 分钟/PULL PENDING 24h/
  PULL STAGED 7 天/PUSH STAGED 30 天。单文件上限 200MB（nginx 同款）；**配额与手机中转
  共池 3GB**（storeMediaTx 与 transfer 两侧都算两表之和）。
- 桌面端 B 侧：`pollInbox()` 末尾 **finally** 里挂 `pollTransferCommands()`（该方法有多个
  early return，直接追加会漏跑）；GET /commands 404=旧服务器进程内静默钉死；hot=true 或
  处理过命令→独立 daemon 线程 5 秒短轮询 120 秒热窗口（常量包可见供测试缩短）。PUSH 落盘
  项目不存在要 POST /fail 触发退款——**与 media 地雷 3 的留置相反**，PUSH 有退款通道。
- 计费官网侧：`/api/internal/transfer`（quote/charge/refund，同机 127.0.0.1 直连 Next，env
  `AWD_TRANSFER_BILLING_SECRET` 未配恒 404 + nginx `^~ /api/internal/` return 404 兜底）；
  定价 `service_pricing` 行 transfer/relay=60 Credits/GB（迁移 24）；流水 kind 仍是
  `service_spend`（meta.service=transfer），**没有新增 ledger kind**。

## 统一账户余额与充值（dev-board#425，spec：aiworkdeck_mobile docs/specs/2026-09-04-mobile-recharge-design.md §3.2）

**本期只有服务端通路，没有任何客户端支付界面**（iOS 内购 #426 / 小程序虚拟支付 #427 /
安卓微信支付 #428 是后面几期）。余额权威只在官网仓（credit_lots + wallet_ledger），
云后端一个字都不存。

- `service/mobile/MobileBillingClient` + `HttpMobileBillingClient` — POST 官网
  `/api/internal/account`，头 `X-Internal-Secret`，四个 action：`resolve`（按已验证
  手机号或邮箱换 accountId，二选一恰好一个，**带 `create` 位**）/ `balance` /
  `create-recharge` / `query`。
  形状照抄 `HttpTransferBillingClient`：配置 `mobile.billing.base-url/secret`
  （env `MOBILE_BILLING_BASE_URL`/`MOBILE_BILLING_SECRET`，**与 TRANSFER_BILLING_SECRET
  是两把不同的密钥**），任一未配 → DISABLED 短路，不发请求。
- **充值总开关 `mobile.billing.recharge-enabled`（env `MOBILE_BILLING_RECHARGE_ENABLED`），
  默认 false，落在 `MobileBillingService`**（复审 N1）。关时 `createRecharge` / `queryRecharge`
  在做任何别的事情之前抛 `DISABLED`——不校参数、不解析身份、**不会走到 `create=true`**、
  不发上游请求。`GET /balance` 是只读的（`create=false`，永不建号），**不受这个开关影响**。
  **这个开关要等 dev-board#434（官网账户注销传导）落地后才允许打开**，理由见红线 8。
- `service/mobile/MobileBillingKind` — **失败判别位的唯一来源**，八个值同时是
  `openapi/mobile-v1.yaml` 里 `Envelope.kind` 的取值集合，四端按它分支。
  `service/mobile/MobileBillingFailureException` 带 kind + outTradeNo，
  `GlobalExceptionHandler.handleMobileBilling` 压成 `{code:1, kind, outTradeNo?, message}`。
- `service/mobile/MobileBillingService` — 身份解析与红线，见下。余额带 30 秒 TTL 缓存，
  **键是 userId**；`query` 查到 paid 即作废该用户缓存。
- `controller/MobileBillingController` — `/api/mobile/billing/{balance,recharge,recharge/status}`，
  鉴权与响应风格同 `MobileRelayController`（`X-Session-Id`；成功裸对象，业务错误
  200 + `{code:1,kind,message}`，未登录 4010）。契约写进 `openapi/mobile-v1.yaml`，
  `MobileApiContractTest.billingEndpointsMatchSpec` 守着。

### 失败分类：判据是「响应体里有没有 `error` 字段」，不是状态码

官网对**鉴权/配置失败**（`AWD_MOBILE_BILLING_SECRET` 未配、header 不符）刻意回**空体 404**
而不是 401/403（对外部探测者与「路由不存在」不可区分）。它与「accountId 查无此人」曾经是
同一个响应，于是**密钥配错一个字符 → 全量用户被告知「还没关联统一账户」，日志里一条痕迹都没有**。
现在两类分开：

| 上游响应 | kind | 备注 |
|---|---|---|
| 空体 / 非 JSON 的 404 | `UNAVAILABLE` | **必须 `log.warn` 并点名密钥/env**，这是运维唯一的线索 |
| 带 `{error:…}` 的 404 | `NOT_FOUND` | 真业务查无此物；resolve 那条再翻成 `NOT_CONNECTED` |
| 409 `order_already_paid` | `ALREADY_PAID` | **连 `outTradeNo` 一起带走**，客户端据此转去查单 |
| 409 `idempotency_conflict` | `IDEMPOTENCY_CONFLICT` | 同上 |
| 其余 4xx | `REJECTED` | `error` 串只进日志 |
| 5xx / 网络 / 解析失败 | `UNAVAILABLE` | **只有 create-recharge 带同一幂等键重试一次** |

### 红线（护栏 `MobileBillingServiceTest`）

1. **绝不复用 `AccountController`/`AccountService`/`MachineAccountGuard` 那条路**：那是机器级
   单例（`~/.aiworkdeck/account.json`），充的是「这台服务器连的那个账户」，与调用者 userId
   无关，server 模式只对 admin 开放。手机端是多租户，复用等于把 A 的钱记到 B 头上。
2. **accountId 只有两个来源**：`account_binding` 里已有的绑定，或用**服务端 User 实体上
   已验证的** phone/verifiedEmail 向官网 resolve 换来的。**绝不接受请求体传入**——
   否则等于对外开了手机号枚举/任意建号的口子（做法同
   `MobileTransferService.requireAccountId`）。资料字段 `email` 不算，只认 `verifiedEmail`。
3. **User 既无手机号也无已验证邮箱 → 报错，不回落任何机器级账户**（licensing-billing.md
   第 17 条的口径，充值比 AI 额度更不能有回落分支）。
4. **审核账号（`ReviewAccountGate`，`auth.review-account.identity`）不许桥接、不许充值**，
   且判定排在绑定查询**之前**——放它去 resolve 等于按审核员的手机号/邮箱在官网建出一个真
   账户，之后那把写在 ASC 审核备注里给外部人看的 6 位固定码就成了进真账户的钥匙。
5. **resolve 出的 accountId 已绑给别的 userId → 拒绝，绝不改绑**（`account_binding` 对
   external_account_id 有唯一约束）。撞唯一约束的并发首调读回对方已提交的行；`saveBinding`
   刻意不带 `@Transactional`，理由同地雷 6（外层事务会被标 rollback-only）。
6. **`idempotencyKey` 必须客户端传入**（发起前落盘，扛 App 被杀），缺失即报错，
   **服务端不代生成**——代生成等于没有幂等键，弱网重试会在官网留下一串各自绑着独立二维码的
   悬挂 pending 单，而官网**没有**针对充值 pending 单的过期回收任务。桌面端
   `AccountController.recharge` 每次 `UUID.randomUUID()` 现生成的写法**不要照抄**。
7. **上游故障绝不能被吞成「余额 0」或「没有账户」**：八个 kind 各有各的用户可读文案，
   失败不写缓存。空体 404 归 UNAVAILABLE 就是这条的直接落点。
8. **读余额永不建号**（dev-board#425 复审 C1）。`resolve` 的 `create` 位只在
   `createRecharge`（用户显式发起充值）为 true，`balance`/`queryRecharge` 一律 false。
   第一版是无条件建号的，而 iOS 设置页的 `.task` 无条件读一次余额——「新用户打开设置页」
   这个纯读动作就会在官网建出一行含明文手机号的真账户，用户全程无感知、未同意；
   而 App 的注销流程（`AccountDeletionService`）只删 Java 侧的 `app_users` 与
   `account_binding`，**从不通知官网**，内部口也没有 delete action，于是 App 自己建的账号
   App 内没有任何路径能删掉——直接撞 App Store 5.1.1(v) 与个人信息保护法的删除权。
   第二期做充值界面时，`create=true` 的那条路要同时补上「注销时通知官网」或
   「建号前明确告知并取得同意」，否则这条红线只是被推迟了。

   **二轮复审 N1 补的护栏**：上面这句「本期没有充值界面所以一次号都不会建」**不是护栏**——
   `POST /api/mobile/billing/recharge` 是随本期一起上线的活端点，也是全站唯一的 `create=true`
   调用方，任何持有有效 `X-Session-Id` 的人直接打它就会在官网建出真账户并发注册赠额，
   触发点只是从「打开设置页」搬到了「直接打这个端点」。所以加了服务端开关
   `mobile.billing.recharge-enabled`（默认 **false**）：关时下单与查单在到达 `create=true`
   之前短路成 `DISABLED`，不发任何上游请求。
   **打开的前提是 dev-board#434（官网账户注销传导）已落地**：注销能传导到官网、官网内部口有
   delete action 之后，才把它置 true。在那之前打开 = 把 App Store 5.1.1(v) 重新放出来。
   护栏：`MobileBillingRechargeDisabledTest`（不配这个键，走 application.yml 的生产默认值）
   与 `MobileBillingServiceTest`（显式 `=true`，测开关开着时行为不变）。
9. **失败一律带机器可读的 `kind`，客户端禁止匹配 message 措辞**。message 经 `LangText`
   在英文部署下会整条变成英文，`code` 又恒为 1——第一版只送 message，于是安卓逐字硬编码
   中文串做分支、小程序判 `code === -1`（云后端业务失败一律 200+code:1，那个分支永远进不去），
   两套都判错。新增失败情形先往 `MobileBillingKind` 加值并同步 yaml 与移动仓
   `contract/fixtures/billing.json`，别在 message 里塞标记。
10. **标识选择要能回退，绑定要能自愈**。同时有手机号与已验证邮箱的用户，手机号被官网按
   站点能力拒（`400 phone_not_supported_on_site`）时改用邮箱再试一次；
   **只在 REJECTED 时回退，NOT_FOUND 绝不回退**——那是官网权威地回答「按这个身份没有账户」，
   回退过去等于把用户悄悄关联到另一个官网账户上。绑定指向的官网账户被注销后，
   `balance`/`createRecharge` 收到 NOT_FOUND 会清掉那行绑定重解析一次
   （`AwdkLoginService.resolveUser` 早有同款自愈分支，方向相反）；`queryRecharge` 不自愈，
   它的 404 分不出是「账户没了」还是「单号不属于你」。

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

- `mvn test -Dtest='MobileRelay*Test,AwdkLoginServiceTest,MediaFileTypeReconcilerTest'`（JDK 21）。
- 统一账户充值（JDK 21）：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test -Dtest='MobileBillingServiceTest,MobileBillingRechargeDisabledTest,HttpMobileBillingClientTest,MobileApiContractTest'`。
  本机 `mvn` 默认走 Homebrew JDK 26，Byte Buddy 不支持，带 @MockBean 的 Spring 上下文会全部加载失败、看起来像代码坏了。
  `HttpMobileBillingClientTest` 用 JDK 自带 `HttpServer` 起本机桩服务回真状态码——空体 404 与
  带 body 的 404 的判据就在 HTTP 层，用 mock 绕过去等于没测。
- 云端冒烟：`curl https://addin.aiworkdeck.com/api/mobile/projects` 无凭据应 401。
- iOS 侧改动跑 `aiworkdeck_mobile` 仓的构建 + TestFlight 通道（fastlane）。
