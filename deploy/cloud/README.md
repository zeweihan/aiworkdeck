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
- **国际站实例（addin.workdeck.ai，新加坡机）env 必须配 `AI_ACCOUNT_BASE_URL=https://www.workdeck.ai`**：
  `ai.account.base-url` 的代码默认值是国内站 `https://www.aiworkdeck.com`，application-cloud.yml 不覆盖。
  漏配的表现是国际站邮箱账号在插件里登录被回「当前站点不支持邮箱方式」（官网 `mail_not_supported_on_site`），
  2026-09-16 实测（dev-board#695）。北京实例保持默认。
- 更新插件任务窗格：office-addin `npm run build:deploy -- --url https://addin.aiworkdeck.com/office-addin`
  → 覆盖 web/office-addin/（**不要**动 web/ 根下的重定向 index.html，也不要再铺 h5）
- DB 备份：`sudo -u postgres pg_dump aiworkdeck_cloud | gzip > /root/backup/...`（建议进 cron）

## 插件跨文件读写（dev-board#717-720，spec `docs/superpowers/specs/2026-09-18-addin-cross-file-design.md`）

插件里的 AI 多了五个参考来源（打开着的文档 / 桌面端项目 / 云端项目 / 官方案件库 / 关联的
GitHub·Gitee 仓库）。**前三个不需要任何部署动作**（窗格登记簿与参考请求登记簿都是进程内存，
门铃流走既有的 `location /api/`）；要配的只有案件库与 git 令牌两项，见 env.example 尾部。

| 项 | 北京 addin | 新加坡 addin | case 实例 |
|---|---|---|---|
| `AWD_REF_INTERNAL_SECRET` | 配，**与 case 同值** | 不配 | 配，与北京 addin 同值 |
| `AWD_REF_CASE_BASE_URL` | `http://127.0.0.1:9797` | 不配 | 不配（它是被问的那一方） |
| `AWD_GIT_TOKEN_SECRET` | 配（独立新生成） | 配（独立新生成） | 不配（案件库不跑插件对话） |

- **国际站没有案件库**：两项不配 = `CaseRefClient.configured()` 为假，`CaseLibrarySource`
  整块缺席，**一次请求都不发**，模型的候选清单里干脆没有这个来源。
- **密钥不许复用**：`AWD_REF_INTERNAL_SECRET` 与 TRANSFER_BILLING_SECRET /
  MOBILE_BILLING_SECRET / COLLAB_DIRECTORY_SECRET 是四把；`AWD_GIT_TOKEN_SECRET` 与
  `AWD_PLATFORM_KEY_SECRET` 是两把。`AWD_GIT_TOKEN_SECRET` 换值 = 存量令牌密文全部解不开
  （用户要重新填一次令牌），迁移换机必须原样带走。
- **nginx：门铃流不用改。** `GET /api/mobile/desktop/stream` 落在既有的
  `location /api/` 上，那里已经是 `proxy_buffering off` + `proxy_read_timeout 3600s`，
  正是 SSE 要的两条；后端另外回 `X-Accel-Buffering: no` 双保险。
- **nginx：两侧各加一条 404 兜底**（见两份 `nginx-*.conf.example`，addin 侧与 case 侧
  都要有；`^~` 不可省，否则会被后面更短的 `location /api/` 抢走）：
  ```nginx
  location ^~ /api/internal/ { return 404; }
  ```
  `/api/internal/ref/{list,read}` 只许从回环进（addin 与 case 同机，走 127.0.0.1:9797 不经
  nginx）。case 侧应用层有四道闸（本实例不提供 / 密钥未配 / 密钥不符 / 来源不是回环，
  **四种都回裸 404**），nginx 这条是第五道，也让公网上的探测面与其余路径长得一模一样。
  addin 侧本来就不提供这对端点（`ref.internal.serve` 只在 case profile 打开，
  配了 `AWD_REF_INTERNAL_SECRET` 也不会把它打开——那把密钥在 addin 上只当出站头用），
  这条规则是给「万一哪天有人把 case profile 的 jar 挂到这台 nginx 后面」留的。
- **更新顺序**：case 实例先上（内部口先在，addin 问过去才有人应），再上 addin 实例，
  最后铺插件静态包（`npm run build:deploy -- --url …`，见上面「更新插件任务窗格」那条）。反过来只是这段时间里
  案件库来源暂时不可用，不会坏别的来源。
- **验收**（上线后各跑一次）：
  - `curl -s -o /dev/null -w '%{http_code}\n' https://case.aiworkdeck.com/api/internal/ref/list -X POST`
    → **404**（nginx 兜底；带不带 `X-Internal-Secret` 都一样）。
  - 在案件库那台上 `curl -X POST http://127.0.0.1:9797/api/internal/ref/list -H 'X-Internal-Secret: <值>'
    -H 'Content-Type: application/json' -d '{"externalAccountId":"<官网账号 id>"}'` → `{"code":0,...}`；
    故意写错密钥 → **404**（不是 401/403，刻意的）。
  - 插件里问一句「项目里有哪些文件可以参考」，`ref_list` 至少列出云端项目那一档；
    桌面端开着时 `desk:` 那一档也在（桌面端要跟着发版才有门铃流，旧版桌面端会被明确说成
    「还没有与云端建立常连（多半是版本较旧）」，不是「离线」）。
