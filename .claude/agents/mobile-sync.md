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

## 验证

- `mvn test -Dtest='MobileRelay*Test,AwdkLoginServiceTest'`（JDK 21）。
- 云端冒烟：`curl https://addin.aiworkdeck.com/api/mobile/projects` 无凭据应 401。
- iOS 侧改动跑 `aiworkdeck_mobile` 仓的构建 + TestFlight 通道（fastlane）。
