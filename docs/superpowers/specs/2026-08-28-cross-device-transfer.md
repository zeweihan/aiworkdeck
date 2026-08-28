# 跨设备项目可见与文件传输（dev-board#250 / #251）

2026-08-28 设计定稿。#250 = 插件端账号级全量项目列表（复用手机目录镜像）；
#251 = 跨设备文件传输（拉取 + 投送两条链路，云中转 + 按大小计费）。
#251 以卡内 2026-08-28 12:16 修正评论为准：主场景是拉取（A 临时调用 B 机项目里的文件，B 必须在线）。

领域文档：`.claude/agents/mobile-sync.md`、`office-addin.md`、`licensing-billing.md`。
既有契约（不重复抄）：目录条目 `{deviceId, key, name}`、key 跨机同号不同物必须连 deviceId 用、
blob 下载红线（2xx + octet-stream 裸字节，禁 302）、配额 3GB 只计未投递 blob、
幂等 UUID 围栏、`/api/mobile/*` 鉴权一律 `X-Session-Id`（awdt_ / 登录会话 / local-mode 三态）。

---

## 一、#250 账号级设备/项目目录

### 1.1 云后端（backend）

**新实体 `MobileDeviceState`**（表 `mobile_device_state`，unique (user_id, device_id)）：
`id` PK、`userId`（not null）、`deviceId`（not null, 64）、`lastSeenAt`（not null, LocalDateTime）。
deviceName 不重复存——目录行（MobileProjectDir）里已有。

**心跳落点**（`MobileRelayStoreService.touchDevice(userId, deviceId)`）：
- `GET /api/mobile/inbox`（桌面端 60 秒轮询，真心跳）与 `PUT /api/mobile/projects`（目录推送）
  两个 controller 入口各调一次。
- touchDevice 自身 try/catch 吞错（心跳失败不能挡住主请求），实现为
  find→update / 无则 insert，撞唯一约束（并发首建）catch 后重查更新一次。
  独立 `@Transactional(REQUIRES_NEW)` 走 self 代理（照抄 storeMedia 的 self 模式）。

**在线判定**：`ONLINE_WINDOW = Duration.ofSeconds(180)`（3 个轮询周期）。
`store.isDeviceOnline(userId, deviceId)`（#251 复用）与 listDevices 共用这一个常量。

**新端点 `GET /api/mobile/devices`**（鉴权同组，裸数组）：
```json
[{ "deviceId": "...", "deviceName": "...或null", "lastSeenAt": "ISO或null",
   "online": true, "projects": [{"key":"12","name":"某项目"}] }]
```
数据源 = 目录行按 deviceId 分组（deviceName 取该组第一个非空值），join 设备心跳表拿
lastSeenAt/online；排序 online 优先、再按 lastSeenAt/updatedAt 倒序。
无目录行的设备不出现（没有项目就没有可见/可传的东西）。

### 1.2 插件窗格（office-addin）

- `api.js` 新增 `fetchMobileDevices({serverUrl, token})`：GET /api/mobile/devices，
  非数组/404/异常一律静默返回 `null`（旧后端降级惯例）。
- `App.vue` 项目下拉：本后端项目为顶层 option（现状不动），其后按设备渲染
  `<optgroup>`，label = `设备名 + （在线/离线）`（i18n）；远程项目 option 的 value 形如
  `remote::<deviceId>::<key>`。`onProjectSelect` 拦截 `remote::` 前缀：**恢复下拉显示值为
  当前本地项目**（照抄 `__new__` 哨兵的恢复写法），#250 阶段给一条 4 秒自隐的头部提示
  （新样式 `.remote-hint`），文案讲清边界：远程设备项目只读、只作跨设备文件传输的
  来源/目标，AI 会话须选本服务上的项目。（#251 会把这个拦截改为打开传输面板。）
- 设备列表随 `refreshProjects()` 一起拉（有 token 才拉），存 `remoteDevices` ref。
- i18n：ZH/EN 同步加键（App.vue 在 i18n.test.js 的 SCAN_FILES 里，裸中文会扫红）。

### 1.3 测试与文档

- 后端：`MobileRelayStoreServiceTest` 加 touch/在线窗口/listDevices 分组与 deviceName
  回落用例；`MobileRelayEndpointIntegrationTest` 加 /devices 往返（含无凭据 4010 信封）。
- 插件：`api.test.js` 加 fetchMobileDevices 两态（正常数组 / 404→null）；`npm test` 全绿。
- 文档：mobile-sync.md 补 MobileDeviceState + /devices + 180 秒在线窗口；
  office-addin.md 补下拉分组与 remote:: 哨兵约定。

---

## 二、#251 跨设备文件传输

### 2.0 总则

- 两条链路共用：OSS/本地中转区（复用 `MobileRelayBlobStore`，key 用 requestId UUID）、
  **配额共池**（与手机中转同一份 3GB：配额计算 = media 未投递 blob + transfer 未投递 blob
  两表之和，`storeMediaTx` 的配额检查一并改）、幂等（client 生成 requestId UUID，
  unique(user_id, request_id)）、计费（见 2.4）。
- 单文件上限 `MAX_TRANSFER_BYTES = 200MB`（nginx 单请求上限同款；超出给可读 code:1）。
- 错误一律 IllegalArgumentException → HTTP 200 + `{code:1,message}`（现网中转约定）。
- B 在线判定复用 #250 的 isDeviceOnline（180 秒）；离线时拉取/清单请求当场拒绝：
  「对方设备不在线，待其开机联网后再试」。投送（PUSH）不要求在线。

### 2.1 云后端实体 `MobileTransferRequest`（表 `mobile_transfer_request`）

| 列 | 说明 |
|---|---|
| id | PK |
| userId | not null |
| requestId | client UUID，unique(user_id, request_id)，复用 MEDIA_ID 正则围栏 |
| kind | LIST / PULL / PUSH（string 8） |
| status | PENDING / STAGED / DONE / DELIVERED / FAILED / EXPIRED（string 16） |
| deviceId | 涉事远程设备（PULL/LIST=来源 B；PUSH=目标 B），not null, 64 |
| projectKey | B 机项目 key，not null, 64 |
| remoteFileId | PULL：B 机 project_file 行 id（字符串透传），nullable |
| fileName | 512，sanitize 同 media（只留最后一段） |
| fileSize | 声明大小；upload 后以实际字节覆盖 |
| storagePath | blob 定位符；**非空 = 占配额**（与 media 同第二重身份）；投递/失败/过期后置空 |
| payloadJson | TEXT：LIST 结果 files 数组 JSON（服务端裁到 2000 条）|
| errorMessage | 1024，FAILED 原因（用户可读） |
| chargedCredits | int nullable |
| chargeLedgerId | string 64 nullable（官网流水 id，退款要用） |
| refundedAt | nullable |
| createdAt / updatedAt | not null |

状态机（TTL 由每小时 `@Scheduled` 清扫执行；退款 = 已扣未退才退）：
- LIST：PENDING →(B 回清单) DONE ｜ →(B 报错) FAILED ｜ →(10 分钟) EXPIRED。不扣费。
- PULL：PENDING（已扣费，等 B 上传）→(B 上传) STAGED →(A save-to-project / ack) DELIVERED
  ｜ →(B 报错 / A cancel) FAILED+退款 ｜ PENDING 24h / STAGED 7 天 → EXPIRED+退款；
  离开 STAGED 一律删 blob。
- PUSH：创建即扣费并从云项目文件复制字节入 blob → STAGED →(B 落盘+ack) DELIVERED
  ｜ STAGED 30 天 → EXPIRED+退款+删 blob。

### 2.2 云后端端点（`/api/mobile/transfer`，鉴权同 /api/mobile 组）

发起端（A：插件会话 / awdt_）：
1. `GET /quote?bytes=N` → `{code:0, credits, balanceCents}`（balanceCents 可能为 null）。
2. `POST /list` body `{deviceId, projectKey, requestId}` → 在线检查 → `{code:0, id}`。
3. `GET /{id}` → `{code:0, transfer:{id,kind,status,fileName,fileSize,credits,error,files,createdAt}}`
   （files 仅 LIST DONE 时带；仅属主可读）。
4. `POST /pull` body `{deviceId, projectKey, remoteFileId, fileName, fileSize, requestId}`
   → 在线检查 → 大小上限 → **扣费**（幂等键 `xfer-`+requestId）→ 建 PULL 行
   → `{code:0, id, credits}`。requestId 撞既有行 = 幂等返回既有 `{id, credits}`。
5. `POST /{id}/save-to-project` body `{projectId}`（插件主路径）：PULL STAGED 限定、
   属主限定、projectId 必须属于该 userId；blob → 云项目文件：**先
   storage.save(显式路径) 后 createFile**（顺序红线同 landAndAck），落
   `跨设备文件/YYYY-MM-DD/原名+requestId前8位`；成功后删 blob、DELIVERED，
   → `{code:0, fileId, name}`。幂等：已 DELIVERED 再调按同名文件查到即返回。
6. `POST /{id}/cancel`：LIST PENDING 或 PULL PENDING 可取消 → FAILED(用户取消)+退款。
7. `POST /push` body `{targetDeviceId, projectKey, fileId, requestId}`：fileId 为云端
   project_file id，**必须校验该文件所在项目属于 userId**；大小上限 → 扣费 → 配额检查
   → 从项目存储复制字节入 blob → PUSH STAGED → `{code:0, id, credits}`。幂等同 /pull。

响应端（B：桌面 awdt_）：
8. `GET /commands?deviceId=` → `{code:0, commands:[...], hot:bool}`。
   commands = 该 (userId, deviceId) 的 LIST PENDING、PULL PENDING、PUSH STAGED 行
   （字段：id, kind, projectKey, remoteFileId, fileName, fileSize）。
   hot = 是否存在 5 分钟内 created/updated 的活跃行（B 据此进入短轮询档）。
   本端点**同时也当心跳**（touchDevice）。
9. `POST /{id}/files` body `{files:[{id,name,path,size}]}`：LIST 应答 → DONE（超 2000 条
   服务端截断）。
10. `POST /{id}/upload` multipart `file`：PULL PENDING → 配额检查（共池）→ blob put →
    实际大小覆盖 fileSize → STAGED。幂等：已 STAGED 重传直接 `{code:0}`。
11. `GET /{id}/content`：PUSH STAGED（或 PULL STAGED，留给未来桌面 A 直取）；
    契约红线同 /inbox/{id}/content。
12. `POST /{id}/ack`：PUSH STAGED → DELIVERED + 删 blob（PULL STAGED 同语义，备用）。
13. `POST /{id}/fail` body `{message}`：B 报确定性失败（文件/项目不存在）→ FAILED +
    退款（如已扣）+ 删 blob（如有）。瞬态网络错误 B **不要**报 fail，留 PENDING 下轮重试。

### 2.3 桌面端（B 侧，`MobileRelayClientService` 扩展）

- 在 `pollInbox()` 的每一轮末尾追加 `pollTransferCommands()`：GET /commands（HTTP 404
  = 旧服务器，静默跳过且本进程内记忆不再打）→ 逐条处理，单条失败不影响其余：
  - LIST：`projectFileService.getFileTree(projectId)` 过滤 isFolder=false，
    组 `{id,name,path,size}`（path=用文件夹名逐级拼 `/`，size=fileSize），POST /files。
    项目不存在/不属本机用户 → POST /fail。
  - PULL：按 remoteFileId 取文件（校验属于该 projectKey 对应项目且项目属本机用户），
    经 storage load 流式读，multipart POST /upload（JDK HttpClient 手写 multipart，
    boundary 随机；60 秒超时不够传 200MB，upload 请求单独 10 分钟超时）。
    文件不存在 → /fail；网络失败 → 留待下轮。
  - PUSH：GET /content（校验 octet-stream，同 landAndAck）→ 落盘到本机 projectKey
    对应项目 `跨设备文件/YYYY-MM-DD/原名+requestId前8位`（**字节先落盘后 createFile**，
    幂等判据同名已在→只补 ack）→ POST /ack。项目不存在 → 留置不 ack（同 media 地雷 3？
    ——不同：PUSH 有退款通道，项目确定不存在应 /fail 退款，不留置）。
- **短轮询档**：响应 hot=true 或本轮处理过任何 command 时，进入热窗口——用独立的
  单线程 executor（不占 @Scheduled 调度线程）以 5 秒间隔连续拉 /commands，
  持续至多 120 秒无新命令即退出；AtomicBoolean 防重入；active() 不过闸即退出。
- 现网量级判断：60 秒基线轮询不变，热窗口只在有传输往来时出现。

### 2.4 计费（设计重点）

**为什么不能走现有平台网关**：云后端刻意不落 awdk_ 明文（桥接每次重验、
`account_binding` 只存 accountId），对已桥接用户没有任何可打 `/api/gateway/*` 的
Bearer；官网也明确否决过「凭 accountId 换任意账户 key」的 S2S 主凭据。

**本设计**：官网新增**窄权限内部记账口**（同机服务器间，权限只有「按 accountId
对 transfer 计价并扣/退 service_spend」一件事，与被否决的「换 key」不同风险类）：

- 官网 `POST /api/internal/transfer/route`（Next route）：
  - 鉴权：header `X-Internal-Secret` === env `AWD_TRANSFER_BILLING_SECRET`；
    env 未配置 → 一律 404（功能不存在）。部署时 nginx 对 `location ^~ /api/internal/`
    直接 return 404（公网第二道），云后端走本机 127.0.0.1:3000 直连 Next。
  - body `{action, accountId, bytes?, idempotencyKey?, refId?, ledgerId?}`：
    - `quote`：按 `service_pricing` 行 `transfer/relay`（unit=gb）计
      `credits = creditsFor(row, bytes/2^30)`，返回 `{ok:true, credits, balanceCents}`。
    - `charge`：幂等（复用 lib/gateway/idempotency，键=cloud 传入）；withTransaction 内
      `chargeTransfer(...)`（lib/gateway/spend.ts 新导出函数：spendCredits kind
      `service_spend`、meta `{service:'transfer', bytes, refId}` + **resyncAiQuota**）；
      → `{ok:true, credits, ledgerId}`；余额不足 → 409 `{error:'no_credits',
      availableCents, requiredCents}`。
    - `refund`：`refundTransfer(ledgerId)`（refundSpend 原批次原额 + resyncAiQuota）；
      已退过幂等返回 ok。
  - accountId → userId 解析复用 /api/account/me 同源（uuid 稳定映射）。
  - `verify-gateway.mts` 的静态护栏扫描名单**必须**加上两个新函数
    （spend.ts 里任何扣费路径漏 resyncAiQuota = 同一笔余额花两遍）。
- 官网迁移：`service_pricing` 新增 `('transfer','relay','gb', cost 50, margin 1.2,
  creditsPerUnit 60, maxUnitsPerCall 3, enabled 1)`（成本基准 = OSS 出网流量约
  0.5 元/GB；60 Credits/GB = 0.6 元/GB，10MB ≈ 1 Credit）。INSERT OR IGNORE，
  不覆盖线上已调的价。
- 顺手补既有缺口：dictionaries zh/en `accountPage.kinds.service_spend`
  （「平台服务消费」/“Service usage”）——目前账户页流水裸显 service_spend。
- ledger kind **不新增**（网关红线：服务名进 meta.service），contract-check KINDS 不动。

**云后端侧**：`service/mobile/TransferBillingClient`（接口）+ Http 实现：
- 配置 `mobile.transfer.billing.base-url`（env `TRANSFER_BILLING_BASE_URL`，默认空 =
  未开通，transfer 全组端点给可读 code:1「跨设备传输未在此服务器开通」）、
  `mobile.transfer.billing.secret`（env `TRANSFER_BILLING_SECRET`）。
- accountId 取 `AccountBindingRepository.findByUserId(userId)`（没有该查询就补），
  未桥接 → code:1「该账户未与官网账户关联，无法计费」。
- 语义：quote/charge/refund；网络失败 → code:1「计费服务暂不可用，请稍后再试」
  （**绝不**免费放行）；no_credits → 文案带所需 Credits 与当前余额、指去官网充值；
  refund 失败只 log.error 不回滚业务状态（行上 refundedAt 仍空，清扫兜底重试一次）。
- 幂等键：charge=`xfer-<requestId>`；refund=`xferrf-<requestId>`。

**费用提示（插件 UI）**：确认前必须显示「预计扣除 N Credits（按文件大小）」+
「传输经官网服务器中转，费用从账户余额扣除」，用户点确认才发 /pull 或 /push。

### 2.5 插件窗格（A 侧 UI）

- 新 `lib/transfer.js`：quote/list/pull/status/saveToProject/push 的 api 封装 +
  轮询辅助（每 3 秒 GET /{id}，LIST 上限 10 分钟、PULL 上限 30 分钟，超时给可读提示；
  等待文案解释对方设备以约 1 分钟一次的频率检查请求，首个响应通常需要 1-3 分钟）。
- 新 `components/TransferPanel.vue`（overlay 面板模式 A + .glass；**加进 i18n.test.js
  的 SCAN_FILES**）：
  - 入口 1：App.vue 项目下拉选中 remote:: 项 → 打开面板并预选该设备/项目（取代 #250
    的提示，提示文案挪进面板顶部说明行）；入口 2：ChatView「+」菜单加「跨设备文件」行。
  - 拉取 tab：设备/项目（来自 fetchMobileDevices，标在线状态；离线置灰并说明）→
    「获取文件清单」（建 LIST + 等待反馈）→ 文件列表（名+大小；>200MB 置灰）→ 选中 →
    显示报价 + 确认 → PULL 进度（等待 B 上传 → 转存本项目）→ 成功后提供
    「附加到对话」（toggleAttachedFile）。
  - 投送 tab：当前项目文件列表（fetchProjectFiles）选一个 → 目标设备/项目 → 报价 +
    确认 → 提交后提示「对方设备上线后自动收取，费用已扣除，长期未送达自动退回」。
- i18n ZH/EN 全量；`lib/transfer.test.js`（stubFetch 惯例）。

### 2.6 测试

- 云后端：`MobileTransferServiceTest`（状态机/幂等/配额共池/TTL 退款/在线闸/属主校验，
  billing 用桩实现记录调用），`MobileTransferEndpointIntegrationTest`（MockMvc 往返 +
  4010 信封 + content 红线）；`MobileRelayStoreServiceTest` 补共池配额用例。
- 桌面端：`MobileRelayClientHttpTest` 加 transfer 命令三类往返（LIST 应答、PULL 上传
  multipart、PUSH 落盘+ack）、404 旧服务器静默、确定性失败上报 /fail、瞬态失败留置。
- 官网：verify-gateway.mts 静态名单扩展自证 + internal route 的 verify 用例
  （quote/charge 幂等/no_credits/refund 原批次）。
- 全量：backend `mvn test`（JDK 21）；office-addin `npm test` + `npm run build`。

### 2.7 部署清单（实施完成后）

1. 官网仓合并 master → CI 自动部署两站；随后两台 ECS：注入
   `AWD_TRANSFER_BILLING_SECRET`（openssl rand -base64 32，两站各自独立），nginx 加
   `location ^~ /api/internal/ { return 404; }`，reload。
2. 云后端 env（两台 addin 主机）：`TRANSFER_BILLING_BASE_URL=http://127.0.0.1:<next端口>`
   `TRANSFER_BILLING_SECRET=<同上>`；重建瘦 jar（先 rm target/backend-*.jar，
   `-Djavacpp.platform=linux-x86_64`），sha256 对账后换入重启。
3. 插件静态资产：`npm run build` + `npm run build:deploy`（两站变体）+ `npm run build:wps`
   覆盖上传。
4. 桌面端（B 侧响应能力）随下一次桌面发版生效——发版前，现网 B 机对传输请求无响应，
   拉取会一直等到 TTL 退款；卡上要写明这一点。

### 2.8 范围裁定（写进落实记录）

- 本轮发起端只做插件窗格；桌面端作为发起端（工作台 UI）后续另卡。
- 插件端「投送当前 Word 文档」（getFileAsync 取 docx 字节）不在本轮，投送只支持
  云项目既有文件。
