# 官方案件库部署（case.aiworkdeck.com）

dev-board#441。「案件库」= 团队服务器 = **另一个完整的 AI WorkDeck 后端实例**，
跑的是同一份 `backend.jar`，只是换了 profile。它对外只提供两件事：

- **git smart-HTTP**（`GitHttpController`，`/git/{repo}.git/*`）——案卷仓库的托管；
- **一小撮 API**——换设备令牌、建项目、成员、`prepare-remote`、云端同步状态。

它**不**跑 AI 对话、不收用户反馈、不做手机影像中转、不服务任何浏览器页面。

| 决策项 | 结论 |
|---|---|
| 机器 | 先不买新 ECS。落在既有北京 ECS `8.152.169.44`（4c8G），与官网 Next.js、插件云后端、PG14、MySQL、宝塔共机 |
| 域名 | `case.aiworkdeck.com`（专用，certbot 本机独立签，不进新加坡续期主控） |
| 数据库 | 既有 PostgreSQL 14 新建独立库 `aiworkdeck_case` + 同名账号 |
| 端口 | `127.0.0.1:9797`（9696 已被插件云后端占用） |
| 进程管理 | systemd `aiworkdeck-case.service`，**专用系统账号 `aiworkdeck-case`** |
| profile | 新建 `application-case.yml`（不复用 cloud，理由写在该文件头部） |
| 身份来源 | 官网账号经 `awdk_` 桥接（`security.awdk-login-enabled=true`），自助注册关闭 |
| 凭据 | `awdt_` 设备令牌。git Basic 的 password 位吃的就是它 |

**红线**：与官网（PM2 :3000）、globalventure（:3001）、插件云后端、宝塔、MySQL 共机，
**既有站点与配置一个字都不动**，nginx 只新增 server 块。

**本方案是按"将来整体搬到专用机器"设计的**——先看下面的「迁移单元」一节再动手，
所有目录选择都是为它服务的。

---

## 一、服务器布局

程序与数据**严格分离**。

```
/opt/aiworkdeck/case/            <- 程序与配置，可重建，不属于迁移单元
  backend.jar                    <- mvn package 产物（本地构建，服务器不编译）
  env                            <- EnvironmentFile（0600 root:aiworkdeck-case），见 env.example
  acme/                          <- certbot webroot

/data/aiworkdeck-case/           <- **迁移单元（数据侧）**。现在是普通目录；
  │                                 将来加数据盘就是挂载点，rsync 一次即迁。
  store/                         <- storage.local.root-path
    repos/                       <- 裸库：project-{id}.git、user-{id}-memory.git、
    │                               project-{id}-memory.git（案件库唯一不可重建的资产）
    repos/memory-worktrees/      <- 记忆仓库的内部物化区
    projects/{id}/               <- 托管项目工作区
    template.docx                <- 新建文档模板（仓库 docs/template.docx）
  home/                          <- 服务账号 HOME：~/.aiworkdeck 状态 + ~/.gitconfig
  var/                           <- systemd WorkingDirectory；plugins/ packs/ skills/
  │                               这几个相对目录落这里（案件库都用不上，但别让它们
  │                               散到 /opt 去，否则迁移会漏）
  backups/                       <- 本机备份落点（备份不出本机，见第五节）
```

**绝不**与 `/opt/aiworkdeck/cloud/data`（插件云后端的数据）混放。两个实例的数据
必须各自独立成堆，否则将来搬案件库时要在同一棵目录树里挑拣文件——那是一定会出错的活。

---

## 二、首次部署

### 1. 本地构建（worktree 内，JDK 21）

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  mvn -B -DskipTests -Djavacpp.platform=linux-x86_64 package
```

`-Djavacpp.platform=linux-x86_64` 不能省：`javacv-platform` 不带这个属性会把
**所有**平台的 natives 都打进去，产物从 424 MB 涨到 1.04 GB。产物大小可以当校验。

### 2. 服务器准备（root）

```bash
# 系统账号（无登录 shell，HOME 指向数据根下）
useradd -r -s /usr/sbin/nologin -d /data/aiworkdeck-case/home aiworkdeck-case

mkdir -p /opt/aiworkdeck/case/acme
mkdir -p /data/aiworkdeck-case/{store,home,var,backups}
chown -R aiworkdeck-case:aiworkdeck-case /data/aiworkdeck-case
chmod 700 /data/aiworkdeck-case          # 与同机的 aiworkdeck（addin）互不可读

# JRE 已随插件云后端装过，确认一下即可
java -version   # 需要 21
```

PostgreSQL（`sudo -u postgres psql`）：

```sql
CREATE USER aiworkdeck_case WITH PASSWORD '...';
CREATE DATABASE aiworkdeck_case OWNER aiworkdeck_case;
\c aiworkdeck_case
CREATE EXTENSION vector;   -- .so 在装插件云后端时已源码编译过，这里只是建扩展
```

`CREATE EXTENSION vector` 严格说是可选的：`PgVectorConfig` 建不出 store 会**静默回退
到进程内存**，案件库又不跑语义检索，功能上无感。建它只是为了让启动日志干净、
不留一条会被误当故障排查的 WARN。

表结构靠 `ddl-auto: update` 自动建，无手动迁移。

### 3. 上传产物

```bash
scp backend/target/backend-*.jar root@8.152.169.44:/opt/aiworkdeck/case/backend.jar
scp docs/template.docx           root@8.152.169.44:/data/aiworkdeck-case/store/template.docx
chown aiworkdeck-case:aiworkdeck-case /data/aiworkdeck-case/store/template.docx
```

### 4. env

```bash
cp deploy/case/env.example /opt/aiworkdeck/case/env   # 填真实值
chown root:aiworkdeck-case /opt/aiworkdeck/case/env
chmod 640 /opt/aiworkdeck/case/env
```

`AWD_PLATFORM_KEY_SECRET` 用 `openssl rand -base64 32` 现场生成，**与插件云后端那把
不复用**，并且**立刻离线抄一份**（丢了 = 存量密文全废，见第五节）。

### 5. JGit 内存护栏（必做，不是可选调优）

这台机器只有 8G 且共机，而 JGit 的 `PackConfig` 默认 `windowMemory=0`（**不限**）。
一个大案卷的首次 clone 会让 delta 搜索把堆吃穿，systemd 的 `MemoryMax` 随后把整个
unit 杀掉——用户看到的是"clone 到一半连接断了"，服务端 journal 里只有一行 oom。
所以在服务账号的 `~/.gitconfig` 里钉死上限：

```bash
install -o aiworkdeck-case -g aiworkdeck-case -m 644 /dev/stdin \
  /data/aiworkdeck-case/home/.gitconfig <<'EOF'
[pack]
	windowMemory = 32m
	threads = 1
	window = 10
	depth = 50
[core]
	bigFileThreshold = 20m
EOF
```

JGit 的配置链是 system → global(`$HOME/.gitconfig`) → repo，所以写在这里对所有
仓库生效，不用逐个裸库配。

> 诚实标注：这几个值来自 JGit `PackConfig` 的默认值与本机 8G 的余量推算，
> **我没有在服务器上实测过**。上线后跑一次真实大案卷的 clone，看 `journalctl -u
> aiworkdeck-case` 有没有 `OutOfMemoryError` 或 cgroup oom，再回来调。

### 6. systemd

```bash
cp deploy/case/aiworkdeck-case.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now aiworkdeck-case
journalctl -u aiworkdeck-case -f
```

首启日志里取随机 admin 初始口令（`journalctl -u aiworkdeck-case | grep 初始口令`），
记下来收好——案件库没有浏览器界面，这个账号只在需要直接调 admin API 时才用得上。

### 7. nginx + 证书

```bash
# DNS：case.aiworkdeck.com A -> 8.152.169.44，生效后
certbot certonly --webroot -w /opt/aiworkdeck/case/acme -d case.aiworkdeck.com
# 宝塔机上落 vhost 目录
cp deploy/case/nginx-case.conf.example \
   /www/server/panel/vhost/nginx/case.aiworkdeck.com.conf
nginx -t && nginx -s reload
```

`limit_req_zone ... zone=awd_auth` 在装插件云后端时已经加过，**不要重复定义**
（`nginx -t` 会报 duplicate）。本域续期完全在北京机本地闭环。

---

## 三、验收清单

按顺序走，每条都要真做，不要"看着像通了"：

1. **启动强不变式**：临时删掉 env 里的 `AWD_PLATFORM_KEY_SECRET` 重启一次，
   服务必须**拒绝启动**（`PlatformAiKeyCipher`）。恢复后再启。
2. `curl https://case.aiworkdeck.com/api/admin/wizard` 通（匿名探活端点）。
3. `curl https://case.aiworkdeck.com/` 返回 404（不该有任何欢迎页）。
4. **注册闸**：`POST /api/auth/register` 被拒。
5. **桥接**：真实 `awdk_` 打 `POST /api/auth/awdk-login` 换到 `awdt_`，
   再带它 `GET /api/projects/my` 通。
6. **git 401 契约**（最容易被 nginx 配置弄坏的一条）：
   ```bash
   curl -i "https://case.aiworkdeck.com/git/1.git/info/refs?service=git-upload-pack"
   ```
   必须是 **401** 且响应头里有 `WWW-Authenticate: Basic realm="AIWorkdeck Git"`。
   收到 403 → 多半被宝塔的防恶意 UA 规则拦了；收到自定义错误页 → `proxy_intercept_errors`
   被打开了。这两种情况下 git 客户端都不会回头送凭据，用户侧表现是"密码没用"。
7. **端到端真跑一遍**：桌面端连接案件库 → 「放进案件库」一个真实案卷（带几十 MB 附件）
   → 换一台机器/另一个账号「取一份案卷」→ 双方各改一处 → 交稿合并。
   只验接口不验这条链等于没验。
8. **大体积**：推一个 > 500 MB 的案卷，确认没有 413、没有超时、
   `/data` 余量与 `journalctl` 都干净。撞 413 就调 nginx 的 `client_max_body_size`。

---

## 四、迁移到专用机器

### 迁移单元是什么

**三样东西，缺一不可，而且必须是同一时刻的快照：**

| # | 内容 | 说明 |
|---|---|---|
| 1 | `/data/aiworkdeck-case/` 整目录 | 裸库 + 工作区 + HOME 状态 + gitconfig。整目录 rsync |
| 2 | PG 库 `aiworkdeck_case` 的 dump | 项目、成员、设备令牌、`ProjectRemote` 映射 |
| 3 | `/opt/aiworkdeck/case/env` 里的 `AWD_PLATFORM_KEY_SECRET` | 换值 = 存量密文全废 |

**不属于迁移单元**（新机重建即可）：`backend.jar`（重新构建或直接 scp 一份）、
systemd unit、nginx conf、Let's Encrypt 证书（新机 certbot 重签）、`DB_PASSWORD`（可换新）。

### 为什么客户端能零改动

桌面端本地存的是 `CloudConnection.serverUrl` = `https://case.aiworkdeck.com`（**域名**，
不是 IP），git 的 origin 是 `https://case.aiworkdeck.com/git/{remoteProjectId}.git`
（`CloudSyncService.shareToCloud` 里拼的）。所以只要满足三个前提，换机器对用户完全无感、
不需要发版、不需要用户重新连接：

1. **域名不变**，换机只改 A 记录；
2. **项目 id 不变**——这就是为什么第 2 项必须是整库 dump/restore。
   重建库或让 PG 重新分配序列，等于所有人本地存的 origin URL 全部指向不存在的仓库，
   而桌面端**没有任何自愈路径**；
3. **设备令牌不变**——令牌哈希在同一个库里，整库迁移自然保住。

这三条是硬红线。任何"顺便清理一下数据重来"的念头都会让全部存量用户失联。

### 迁移步骤

```bash
# —— 停机前：先热同步一轮，把停机窗口压到分钟级 ——
rsync -aHAX --numeric-ids /data/aiworkdeck-case/ new:/data/aiworkdeck-case/

# —— 停机窗口开始 ——
systemctl stop aiworkdeck-case                       # 旧机

sudo -u postgres pg_dump -Fc aiworkdeck_case > /data/aiworkdeck-case/backups/migrate.dump
rsync -aHAX --numeric-ids --delete /data/aiworkdeck-case/ new:/data/aiworkdeck-case/

# —— 新机 ——
# 前置：JRE21 / PG14(+vector) / nginx / useradd aiworkdeck-case / 目录属主
sudo -u postgres createuser aiworkdeck_case
sudo -u postgres createdb  aiworkdeck_case -O aiworkdeck_case
sudo -u postgres pg_restore -d aiworkdeck_case /data/aiworkdeck-case/backups/migrate.dump
# env：AWD_PLATFORM_KEY_SECRET 原样抄过来，DB_PASSWORD 可换新
systemctl enable --now aiworkdeck-case
# nginx conf 照搬（改 proxy_pass 端口如有变化），certbot 签新证书

# —— 切流量 ——
# 提前 24h 把 case.aiworkdeck.com 的 TTL 调到 60s，此刻改 A 记录
# —— 停机窗口结束 ——
```

切完之后按第三节的 1/2/6/7 条重跑一遍验收（尤其第 7 条：拿一个**存量**案卷做
「取一份案卷」，这是唯一能证明 id 与令牌都没坏的检查）。

**旧机留一周只读再删**，不要当天清理。

### 迁移时可以顺手调大的东西

上专机（假设 ≥16G 独占）后：

| 项 | 共机（现在） | 专机 |
|---|---|---|
| `-Xmx` | 1024m | 4g |
| `MemoryHigh` / `MemoryMax` | 1280M / 1536M | 去掉 MemoryHigh，MemoryMax 6g |
| `CPUWeight` | 70 | 去掉（不再需要给官网让路） |
| `pack.windowMemory` | 32m | 256m |
| `pack.threads` | 1 | 2–4 |
| nginx `client_max_body_size` (/git/) | 4g | 按数据盘余量定 |

---

## 五、备份

### 为什么必须单独说

官网那套 `backup-data.mjs`（两台机的 cron）**不覆盖案件库**：它的 `--extra` 只加了
`/www/wwwroot/plugin-packs` 与 `/opt/aiworkdeck/cloud/data`。案件库的数据在
`/data/aiworkdeck-case/`，现在处于**零备份状态**——上线前必须补上。

（本节按要求只写"要备什么、怎么验"，不写脚本。）

### 备什么

| 优先级 | 内容 | 可重建？ |
|---|---|---|
| P0 | `/data/aiworkdeck-case/store/repos/` | **不可重建**。律师的案卷全文与全部历史，丢了就是丢了 |
| P0 | PG 库 `aiworkdeck_case` | 不可重建。丢了 = 项目 id / 成员 / 设备令牌全废，等于全部用户失联 |
| P0 | `AWD_PLATFORM_KEY_SECRET` | 不可重建。**离线单独保管，不和数据放一起** |
| P1 | `/data/aiworkdeck-case/home/.aiworkdeck/` | 账户/权益状态文件 |
| P2 | `/data/aiworkdeck-case/store/projects/` | 托管项目工作区。理论上能从裸库物化回来，但省事就一起备 |

### 口径红线（沿用既有服务器备份口径）

- **备份不出本机**。北京机的用户数据同步到境外 = 数据出境，与隐私政策"承诺全境内"
  冲突。产物落 `/data/aiworkdeck-case/backups/`，权限 0600。
- **保留份数只许调小不许调大**（受隐私政策保留期承诺约束）。
- 机器级灾难兜底靠迁移演练（第四节）与云盘快照，不做异地全量。

### 增量，不要每天全量 tar

`repos/` 会长到几十 GB，每天 tar 一次既写穿磁盘也写穿保留窗口。建议形状：

- **每日**：`repos/` 用 rsync 到本机 `backups/repos-daily/`（`--link-dest` 做硬链接
  增量，一份全量的空间开销换 N 天的历史）；PG 每日 `pg_dump -Fc`。
- **每周**：一次 `tar` 全量归档，便于整份带走。

### 一致性：dump 必须早于 rsync

git 裸库和 PG 库是**两个必须互相对得上**的东西。理想做法是 `systemctl stop` 之后再备
（案件库不是 7×24 的交互服务，凌晨停 2 分钟没人受影响）。若坚持热备，**顺序必须是
先 pg_dump 再 rsync repos**：

- 先 DB(T1) 后仓库(T2>T1)：恢复后仓库里有一些 DB 不知道的新 ref —— 多余数据，无害。
- 反过来：DB 里记着一个 T1 时还不存在的 ref —— 桌面端一取就报"取不到"，**真的坏**。

### 恢复怎么验

不是"文件在不在"，是**内容级对账**。备份前先记下基线：

```bash
# 基线：挑一个活跃项目，记下它主线的 sha
curl -H "Authorization: Basic <base64 user:awdt_...>" \
  "https://case.aiworkdeck.com/git/{id}.git/info/refs?service=git-upload-pack" | head
```

恢复演练（在一台干净机器上，**不要在生产上试**）：

1. restore PG dump + 展开 `repos/` 备份；
2. 起服务，`GET /api/admin/wizard` 通；
3. 用一个**真实的存量设备令牌** `git clone https://<演练机>/git/{id}.git`，
   拿到的 HEAD sha 必须与基线逐字相同；
4. 打开 clone 出来的案卷，随机抽一个文件对比字节。

第 3 步是关键：它同时证明了 PG（令牌与成员判定）、裸库（对象与 ref）、
以及两者的一致性。只验第 1、2 步等于什么都没验。

---

## 六、日常运维

```bash
journalctl -u aiworkdeck-case -f                      # 日志
systemctl restart aiworkdeck-case                     # 重启
du -sh /data/aiworkdeck-case/store/repos              # 盘占用（长得最快的就是它）
```

- **更新后端**：本地重新 `package`（带 `-Djavacpp.platform=linux-x86_64`）→
  scp 覆盖 `/opt/aiworkdeck/case/backend.jar` → `systemctl restart aiworkdeck-case`。
  判据同插件云后端：`git diff v<上一版>..v<本版> -- backend/` 非空就要更新。
  **两个实例是各自独立的 jar 文件，更新一个不会更新另一个**——很容易只发了 addin
  忘了 case，发版清单里要分两行写。
- **仓库 GC**：`RepoMaintenanceJob` 每天 03:30 自动跑（重打包 + 清不可达对象），
  不做任何历史清理。它跟备份的 cron 时间要错开。

---

## 七、还没定的事

- 桌面端「连接团队案件库」当前走 `CloudSyncService.connect` →
  `POST /api/auth/device-token`（**账号 + 口令**）。而案件库开了
  `registration-mode: closed` + `awdk-login-enabled: true`，经桥接建的账号是**无口令
  账号**（`UserService` 的口令哨兵前缀），这条路走不通。所以要么桌面端补一条
  awdk 桥接的连接方式，要么在案件库上另开口子。本目录只准备部署侧，
  这一条属于客户端改造，需要主会话确认由谁做、什么时候做——**在它落地之前，
  案件库对普通用户是连不上的**。
- 8G 共机的内存余量是纸面推算（addin `MemoryMax=2560M` + case `1536M` + PG + MySQL +
  Next.js），上线前需要在机器上 `free -m` / `systemd-cgtop` 实核一次。
