# 插件云后端部署（addin.aiworkdeck.com）

官方托管的后端实例：Office 插件经 awdk_ 桥接连接。**浏览器 Web 客户端（h5 + LOWA 编辑器）
已于 2026-08-19 退役**（维护者拍板：裸露登录页令人困惑）——根路径 index.html 现在是一个
跳转 aiworkdeck.com 的静态页（原件备份为 `index.html.h5-retired-20260819`），
**更新部署时不要再铺 build:h5 / build:zetaoffice 产物**；`web/` 下的 assets/static/zetaoffice
是退役残留，留在原地无害，不要顺手清理（清理与否待单独拍板）。
部署形态与决策记录（2026-08-07，维护者拍板）：

| 决策项 | 结论 |
|---|---|
| 机器/域名 | 北京 ECS 8.152.169.44 / addin.aiworkdeck.com（certbot 独立签，不进新加坡续期主控） |
| 数据库 | 既有 PostgreSQL 14 新建独立库 aiworkdeck_cloud + 源码编译 pgvector |
| 部署范围 | 后端 + 插件任务窗格静态页；h5 前端与 LOWA 编辑器 2026-08-19 起不再部署；不带 Python 附属服务（pptx/mineru/kokoro 云端不可用） |
| 进程管理 | systemd（aiworkdeck-cloud.service），专用系统账号 aiworkdeck |
| 会话存储 | 已改 DB 落库（UserSession，7 天滑动过期），重启不掉线 |

红线：与官网（PM2 :3000）、globalventure（:3001）、宝塔、MySQL 共机，
**既有站点与配置一个字都不动**，nginx 只新增 server 块。

## 服务器布局

```
/opt/aiworkdeck/cloud/
  backend.jar          <- mvn package 产物（本地构建，服务器不编译）
  env                  <- EnvironmentFile（0600），见 env.example
  data/                <- storage.local.root-path（文档文件）
  data/template.docx   <- 新建文档模板（repo docs/template.docx）
  home/                <- 服务账号 HOME（~/.aiworkdeck 状态文件落这里）
  acme/                <- certbot webroot
  web/                 <- index.html 为跳官网的静态重定向页（h5 已退役，见上）
  web/office-addin/    <- Office 插件任务窗格（office-addin 构建产物，见 office-addin.md）
  （/feedback-console/ 不在 web/ 下：维护者反馈控制台由 backend.jar 的
   classpath:/static/ 直接托管，nginx 有一条 location 反代给后端——
   更新它 = 正常更新后端 jar，见 nginx-addin.conf.example）
  web/zetaoffice/      <- 退役残留（原 h5 的 LOWA 引擎载荷），留置不清理
```

## 首次部署步骤（2026-08-07 实录见 PR 描述）

1. 本地构建（worktree 内，JDK 21）：
   ```bash
   cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -DskipTests package
   #（历史步骤，2026-08-19 起不再执行：build:h5 / build:zetaoffice / fetch-lowa-assets）
   ```
2. 服务器准备（root）：
   - `useradd -r -m -d /opt/aiworkdeck/cloud/home aiworkdeck`
   - `apt-get install openjdk-21-jre-headless`
   - PostgreSQL：建库 aiworkdeck_cloud + 同名账号；pgvector 源码编译
     （`apt-get install postgresql-server-dev-14 build-essential git` →
     `git clone --branch v0.7.4 https://github.com/pgvector/pgvector` → `make && make install`，
     国内网络克隆失败时用 ghproxy 镜像）→ 库内 `CREATE EXTENSION vector;`
3. 上传产物到 /opt/aiworkdeck/cloud/（scp；目录属主 aiworkdeck）。
4. `cp env.example /opt/aiworkdeck/cloud/env` 填真实值，`chmod 600`。
   AWD_PLATFORM_KEY_SECRET 用 `openssl rand -base64 32` 现场生成。
5. nginx：http 块加 limit_req_zone（见 nginx-addin.conf.example 尾注），
   vhost 目录新增 addin.aiworkdeck.com.conf，`nginx -t && nginx -s reload`。
6. 证书：DNS A 记录 addin -> 8.152.169.44 生效后
   `certbot certonly --webroot -w /opt/aiworkdeck/cloud/acme -d addin.aiworkdeck.com`。
   本域续期完全在北京机本地闭环（与 www/@ 的新加坡续期主控无关）。
7. `cp aiworkdeck-cloud.service /etc/systemd/system/` →
   `systemctl daemon-reload && systemctl enable --now aiworkdeck-cloud`。
8. 首启日志里取随机 admin 初始口令（`journalctl -u aiworkdeck-cloud | grep 初始口令`），
   登录 admin 页立即改密；在系统管理里将 AI 供应商设为平台通道。

## 验收清单（上线时全部实测过）

- 删掉 env 里的 AWD_PLATFORM_KEY_SECRET 重启一次，服务必须**拒绝启动**
  （PlatformAiKeyCipher 强不变式），恢复后再启。
- 公网 `POST /api/auth/awdk-login`（真实 awdk_）换 awdt_，再 `GET /api/projects/my` 通。
- 公网 `POST /api/auth/account-login/send-code`（真实手机号）收到短信；
  `POST /api/auth/account-login`（`{phone, code}`）换 awdt_ 通，错码回 code=1 且**不带 4010**。
  这两条与 awdk-login 共用 `security.awdk-login-enabled`，没有单独的开关。
- `GET /api/platform-ai/key/status` 回本账号额度（未分配额度=业务错误，不是 500）。
- `POST /api/auth/register` 被注册闸拒绝。
- 非 admin 的 awdt_ 访问 `/api/account/status` 被 MachineAccountGuard 挡下。
- Office 插件真机：设置页用手机号+验证码登录（默认地址已内置），发消息收到流式回复；
  「高级设置」里的 awdk_ Key 与设备令牌两条兜底路径也各走一遍。

## 日常运维

- 日志：`journalctl -u aiworkdeck-cloud -f`
- 更新后端：本地重新 package → scp 覆盖 backend.jar → `systemctl restart aiworkdeck-cloud`

  **package 必须带 `-Djavacpp.platform=linux-x86_64`**：
  ```bash
  cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) \
    mvn -B -DskipTests -Djavacpp.platform=linux-x86_64 package
  ```
  `javacv-platform` 不加这个属性会把**所有**平台的 natives 都打进去
  （windows / macosx / ios / ppc64le…），产物从 424 MB 涨到 1.04 GB。
  多出来的 600 MB 在 Linux 上一行都用不到，纯粹白传白占盘。
  产物大小可以当校验：和线上那份差不多（±1 MB）才对。

## 手机影像云中转的 OSS 存储（dev-board#236）

- blob 不再落 ECS 本地盘，走平台私有桶：北京 `awd-mobile-relay`（cn-beijing），
  国际站 `awd-mobile-relay-intl`（ap-southeast-1，国际站账号）。桶生命周期 35 天
  过期 + 7 天清失败分片，只是兜底——主删除机制仍是桌面端 ACK 即删（代码 TTL 30 天次之）。
- RAM 子用户 `awd-mobile-relay`：仅该桶 Get/Put/Delete/List/HeadObject + GetBucketStat，
  secret 位置见 EXTERNAL_SERVICES.md §1.1。
- 开关与凭证 = env 里的 `MOBILE_RELAY_OSS_*` 五项（见 env.example）；不配即回落本地盘
  （desktop/团队服务器形态）。enabled=true 而配置不全会拒绝启动，属刻意设计。
- 存量本地 blob 无需搬迁：storagePath 以 `/` 开头的旧行走双读兼容，最迟 30 天被
  ACK/TTL 消化。
  （会话已 DB 落库，重启不掉浏览器登录态）
- 更新插件任务窗格：office-addin `npm run build:deploy -- --url https://addin.aiworkdeck.com/office-addin`
  → 覆盖 web/office-addin/（**不要**动 web/ 根下的重定向 index.html，也不要再铺 h5）
- DB 备份：`sudo -u postgres pg_dump aiworkdeck_cloud | gzip > /root/backup/...`（建议进 cron）
