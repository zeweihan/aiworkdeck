# 插件云后端部署（addin.aiworkdeck.com）

官方托管的后端实例：Office 插件经 awdk_ 桥接连接；同域名同时提供浏览器 Web 客户端
（h5 + LOWA 编辑器）。部署形态与决策记录（2026-08-07，维护者拍板）：

| 决策项 | 结论 |
|---|---|
| 机器/域名 | 北京 ECS 8.152.169.44 / addin.aiworkdeck.com（certbot 独立签，不进新加坡续期主控） |
| 数据库 | 既有 PostgreSQL 14 新建独立库 aiworkdeck_cloud + 源码编译 pgvector |
| 部署范围 | 后端 + h5 前端 + LOWA 编辑器；不带 Python 附属服务（pptx/mineru/kokoro 云端不可用） |
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
  web/                 <- frontend/dist/build/h5/*
  web/zetaoffice/      <- frontend/dist/zetaoffice/*（含 lowa/ 引擎与 CJK 字体）
```

## 首次部署步骤（2026-08-07 实录见 PR 描述）

1. 本地构建（worktree 内，JDK 21）：
   ```bash
   cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -DskipTests package
   cd frontend && npm run build:h5 && npm run build:zetaoffice
   node desktop/scripts/fetch-lowa-assets.js
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
  （会话已 DB 落库，重启不掉浏览器登录态）
- 更新前端：重新 build:h5 / build:zetaoffice → rsync 覆盖 web/
- DB 备份：`sudo -u postgres pg_dump aiworkdeck_cloud | gzip > /root/backup/...`（建议进 cron）
