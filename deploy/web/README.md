# Web 服务器版部署（Phase A2）

背景与整体规划见 [docs/HARMONYOS_PLAN.md](../../docs/HARMONYOS_PLAN.md)。
这是「瘦客户端」的服务端形态：鸿蒙电脑（或任何设备）用浏览器访问；
后续的鸿蒙 Electron 壳（Phase B）也连同一个后端。

> 当前限制：LibreOffice 编辑器在纯浏览器里的通道（A1，iframe bridge）尚未实现，
> 落地前编辑器功能在 Web 版不可用，其余功能（项目/文件/AI 编排/解析/TTS）不受影响。

## 构建产物

```bash
cd frontend && npm run build:h5 && npm run build:zetaoffice
node desktop/scripts/fetch-lowa-assets.js   # LOWA 运行时 + CJK 字体（落到 frontend/dist/zetaoffice/lowa/）
mvn -B -DskipTests -f backend/pom.xml package
```

## 服务器布局

```
/opt/aiworkdeck/web/            <- frontend/dist/build/h5/*
/opt/aiworkdeck/web/zetaoffice/ <- frontend/dist/zetaoffice/*
/opt/aiworkdeck/web/probe/      <- deploy/web/probe/*（可选，浏览器能力探针）
后端 jar：java -jar backend.jar --spring.profiles.active=prod   # :9696，只监听 127.0.0.1
Python 服务：docker compose up（仅内网端口，由后端编排，不对外暴露）
```

nginx 用 [nginx.conf.example](nginx.conf.example)，改域名和证书即可。
前端构建时无需设置 `VITE_API_BASE_URL`（默认同源，nginx 反代 `/api/`）。

## 鸿蒙浏览器能力探针（probe/）

判断鸿蒙设备浏览器能否跑 LOWA，只需打开 `https://<域名>/probe/` 看四项是否全绿：
crossOriginIsolated、SharedArrayBuffer、共享 WASM Memory、Worker 传递 SAB。

- 探针页自带 COI service worker 兜底（`coi-sw.js`）：即使托管方加不了 COOP/COEP 头
  （如对象存储/静态托管），首次加载注册 SW 后自动刷新一次也能得出结论。
  正式部署走 nginx 头方案，SW 只是探针的便携兜底。
- 没有真机：华为体验店现场打开 10 分钟测完，或 DevEco Studio 鸿蒙 PC 模拟器。

## 桌面端连这台服务器当「团队案件库」

律师在桌面端看到的协作术语是「团队案件库」——把案卷放进去、交稿、取回最新稿、
加案件参与人。**界面上没有填服务器地址的地方**（dev-board#440 起，admin 的「团队案件库」
分区与协作抽屉里的连库表单一起撤掉了）：普通用户一律连官方案件库
`https://case.aiworkdeck.com`。要让桌面端改连**这台**服务器，选一条：

### A. 把「官方案件库」整体指到这台服务器（推荐，律师端零操作）

在**每台桌面端**的后端进程上设一个环境变量（桌面壳把环境透传给内嵌后端）：

```
CLOUD_COLLAB_BASE_URL=https://team.你所里.com
```

对应配置项是 `cloud.collab.base-url`（`backend/src/main/resources/application.yml`）。
必须 https（回环 http 只供本地联调）——这条通道上跑的是长期设备令牌。
设了之后，律师在协作抽屉点「放进团队案件库」就直接连到这台服务器，全程不填任何地址。

这条路要求服务器侧同时开账户桥（桌面端拿本机的官网账户 Key 去换设备令牌）：

```yaml
security:
  awdk-login-enabled: true      # prod 档默认 false
ai:
  account:
    base-url: https://aiworkdeck.com   # 服务器出站校验 Key 用
```

首次登录会按官网账户的 `accountId` 在本服务器自动建一个 `awd_` 前缀用户，
所以律所同事各自用自己的 AI WorkDeck 账号即可，不必再开一套服务器账号。
官方案件库自己就是这么跑的，配置样板见 `backend/src/main/resources/application-case.yml`。

### B. 用这台服务器自己的账号连（不依赖 aiworkdeck.com）

服务器保持默认（`awdk-login-enabled: false`），管理员在服务器上给每位同事开账号，
每台桌面端各调一次本机后端的端点（端点保留，只是没有界面入口）：

```bash
curl -X POST http://127.0.0.1:9696/api/cloud/connect \
  -H 'Content-Type: application/json' \
  -d '{"serverUrl":"https://team.你所里.com","username":"张三","password":"……","deviceName":"办公室台式机"}'
```

本机只保存这次换回来的长期设备令牌，不保存口令；
`POST /api/cloud/connections/{id}/disconnect` 断开时会回服务器把令牌作废。
连上之后协作抽屉里的「放进团队案件库」就会用这条连接（**恰好一条连接时**才会用它；
本机同时存着多条连接时会退回去连官方案件库，所以别在一台机器上连两个库）。

服务器首次启动会建一个 admin 账号，随机初始口令只在启动日志里出现一次，
取走改密后再给同事开账号。

### 记忆同步（可选）

AI 记忆走另一套独立 Git 仓库（`/git/user-{id}-memory.git`、`/git/project-{id}-memory.git`），
与案卷仓库互不相干。admin 里的自填地址面板同样已撤，配置改调本机后端：

```bash
curl -X POST http://127.0.0.1:9696/api/memory-sync/user-123-memory/remote \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://team.你所里.com/git/user-123-memory.git","username":"张三","secret":"awdt_……"}'
```

`secret` 位吃的就是设备令牌（与 git Basic 的 password 位同一种凭据）。

## 安全底线

- Python 服务端口（8001/5001 等）绝不对公网开放，只走 127.0.0.1/内网。
- 后端 9696 只监听本机，由 nginx 统一入口 + TLS。
- 管理页 `/api/admin` 依赖登录态（requireAdmin），公网部署务必改默认口令。
