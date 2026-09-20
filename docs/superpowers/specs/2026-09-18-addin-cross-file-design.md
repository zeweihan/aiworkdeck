# Office/WPS 插件跨文件读写 设计

- 日期：2026-09-18
- 卡：dev-board#717（打开文档互读互改）、#718（桌面端项目文件按需读取）、#719（桌面端与云端常连）、#720（git 来源）
- 领域：office-addin、ai-chat、mobile-sync、version-control

## 0. 维护者拍板（2026-09-18）

1. AI 读参考材料（抽出的文字）**不计费**；整文件 PULL/PUSH 照旧按 transfer 定价计费。
2. 参考材料经云端**内存中转、不落盘**；新数据用途与新出站请求写进 `legal/PRIVACY.md`。
3. **双向可写**：A 窗格可改 B，但修订记录与痕迹必须在 **B 自己的插件窗格**里明示。
4. 覆盖不装桌面端的用户：来源包括**我们的云端 git（官方案件库）+ GitHub/Gitee**。
5. 原则：云端只做中转，权威源是用户自己的项目（桌面端或 git）。
6. D 决策：**只有打开着的文档可写**，其余来源（桌面端项目、云端项目、git）一律只读。要改未打开的 C，AI 请用户打开；C 在本机桌面项目时桌面端可代为打开。

## 1. 用户可见行为

- 在 A 的窗格里说「根据 B 第 3 页改 A」：AI 列出候选 → 读 B 第 3 页 → 改 A。
- 说「把 B 里的甲方名称也改掉」：AI 经 B 窗格改 B；B 窗格弹横幅「来自《A》会话的 AI 刚修改了本文档 N 处，修订已标记」，「修订记录」面板新增条目。
- 说「参考 C 改 A」（C 未打开）：AI 依次在桌面端项目、云端项目、案件库、已关联 git 仓库里找 C，读正文后改 A。
- 说「顺手改 C」（C 未打开）：AI 说明 C 未打开、不能直接改；C 在本机桌面项目时提供「帮我打开 C」，打开后在 C 的窗格里继续。
- 任何来源不可达（B 窗格已关、桌面端离线、git 令牌失效）：AI 如实说明原因与出路，不猜内容。

## 2. 架构总览

```
A 窗格 ──chat──> 云后端（addin.*）
                   │  ReferenceSourceService（解析 ref、按来源分派）
                   ├─ OpenDocSource   ── PaneRegistry ──client_action──> B 窗格（读/写）
                   ├─ DesktopSource   ── DesktopDoorbell(SSE) ──> 桌面端 ──READ 结果──> 云后端
                   ├─ CloudProjectSource（既有 FileTools 读路径）
                   ├─ CaseLibrarySource ── 127.0.0.1 内部 API ──> case 实例（JGit 读裸仓）
                   └─ GitProviderSource ── GitHub / Gitee contents API（内存）
```

单区域单 JVM（北京 addin.aiworkdeck.com、新加坡 addin.workdeck.ai 各一个），所有登记簿为进程内存。横向扩容需粘性路由 + 共享登记簿，本设计不做。

## 3. AI 工具契约

Office/WPS 会话（`ClientCapabilityService.capabilityOf == OFFICE`）新增四个工具。前缀 `ref_` 不属于 `doc_/sheet_/slide_/office_`，按 `isToolVisible` 现有规则会对所有会话可见，因此**在 `isToolVisible` 里显式限定 `ref_` 只对 OFFICE 会话可见**（LOWA 会话已有项目文件工具，不引入）。

| 工具 | 参数 | 返回 |
|---|---|---|
| `ref_list` | `query`（可空，名称关键字）、`source`（可空：open/desktop/cloud/case/git） | 候选清单，每项 `{ref, source, name, path, host?, updatedAt?}`，≤100 条；各来源失败以一行说明并列，不整体失败 |
| `ref_read` | `ref`、`locator`（可空） | 文本，≤200k 字符，截断标注 `...(截断)`；图片/扫描件走 OCR |
| `ref_edit` | `ref`（必须是 `open:` 来源）、`command`、`args` | 与 office_* 相同的结果结构 |
| `ref_open` | `ref`（必须是 `desk:` 来源且 LIST 标了 `openable`） | 桌面端用系统默认程序打开该文件的结果（见 6.1） |

`ref` 形态（不透明字符串，模型只复制不构造）：
- `open:<paneId>`
- `desk:<deviceId>:<projectKey>:<path>`
- `cloud:<fileId>`
- `case:<remoteProjectId>:<path>`
- `git:<repoLinkId>:<path>`

`locator` 语义：`page:N`（Word）、`slide:N`（PPT）、`sheet:<名>[!A1:D20]`（Excel）、`heading:<文字>`（Word 标题所辖段落）。按 locator 精确截取只对 `open:` 来源生效；未打开的来源（desk/cloud/case/git）抽出的是纯文本、没有页的概念，带 locator 时返回全文并在开头写明「未打开的文件无法按页定位，以下为全文」。打开文档上不支持的组合返回明确错误文案，不静默退回全文。

**末位约束**：Office 会话现有的「本会话只能编辑打开的这一份文档」硬规则（`ContextAssemblerService` 482 行附近，中英两处）改写为：可读任何参考来源；可经 `ref_edit` 改**其他打开的文档**；未打开的文件一律不改，请用户打开。仍挂本段末位。

## 4. 打开文档互读互改（#717）

### 4.1 窗格登记（PaneRegistry）
- 窗格启动后每 30 秒 `POST /api/addin/panes/heartbeat`：`{paneId, host(word|excel|powerpoint), family(office|wps), docName, projectId, conversationId}`。`paneId` 复用窗格建 SSE 时已上送的 `clientId: paneId`（chatSession.js），不另造。
- 服务端按 userId 持有 `Map<paneId, PaneInfo>`，90 秒无心跳即过期；窗格卸载时 `navigator.sendBeacon` 发 `/panes/bye`。
- 切换会话/项目后立即补发一次心跳（conversationId 变化即重登记）。
- `ref_list(source=open)` 排除发起方自身 paneId。

### 4.2 跨窗格下发
- `OfficeBridgeService` 新增 `executeOnPane(PaneInfo target, String command, args, CrossPaneOrigin origin)`：复用 pendingRequests 与 CompletableFuture，SSE 发往**目标窗格当前 conversationId**；载荷多一个 `origin: {paneId, docName, conversationId}` 字段（B 窗格据此记录来源）。
- `OfficeResultController` 的归属校验不变（目标会话同属该用户）。
- 目标窗格 SSE 未连接：立即返回「《B》的 AI WorkDeck 窗格当前没有连着，请在 B 里打开窗格后重试」，不等超时。
- 超时沿用 30s / 批量 120s。

### 4.3 B 窗格明示痕迹
- 收到带 `origin` 的写入类命令：
  - Word：强制 `trackAll`（既有 `withTracking`，门槛 `trackingSupported()` = WordApi 1.4），宿主不支持时**拒绝执行**并回错「本机 Word 版本无法标记修订，已拒绝跨文档修改」——无痕迹的跨文档写入不允许发生。
  - Excel / PPT：执行前按命令读取受影响区域原值（Excel 单元格 values/formulas；PPT 形状文本），与新值一起记入修订记录，支持一键撤销（写回原值）。撤销前比对当前值，已被用户再改过则提示冲突不覆盖。
  - WPS：同上三分支，走 wps*Handlers；WPS 文字修订开关用 `TrackRevisions`。
- 新组件 `components/RevisionLogPanel.vue` + `lib/revisionLog.js`（模块级 store，按文档持久化到 localStorage `awd_addin_revlog_{docKey}`，上限 200 条）：条目 `{id, time, originDocName, originConversationId, command, summary, before?, after?, undoable}`；操作：定位（Word 用修订定位/搜索，Excel 选中区域，PPT 跳页）、撤销、清空已读。
- 横幅：收到跨文档写入后显示，点击打开修订记录面板；本窗格自身会话的写入不弹横幅（仍可选记入，v1 只记跨文档来源）。
- 入口：头部增加「修订记录」按钮，有未读条目时带计数角标。

### 4.4 读取与按页定位
- 读：复用 READ_ONLY_COMMANDS 的实现（`get_text`/`excel_get_range`/`ppt_get_slide_details` 等），新增窗格命令 `read_for_reference {locator}` 统一出口。
- Word `page:N`：宿主支持 `WordApiDesktop 1.2`（`Pane.pages` / `Page.getRange()`）时按页取；否则返回「本机 Word 不支持按页读取，可改用标题或关键词」。**Mac Word 是否支持需真机验证。** WPS 文字用 `Range.Information(wdActiveEndPageNumber)` 或 `GoTo(wdGoToPage)`，同样真机验证。
- 发起方 A 永不直接读 B 的文件字节。

## 5. 桌面端常连（#719）

- 云端新增 `GET /api/mobile/desktop/stream`（SSE，awdt_ 鉴权，按 (userId, deviceId) 单连接，后连顶掉先连），15 秒心跳；有待办时推 `event: nudge`，载荷只含类型，不含内容。
- 桌面端 `MobileRelayClientService` 起一个 daemon 线程持有该流（java.net.http 流式读取），收到 nudge 立刻执行既有 `pollTransferCommands()` 与新的 `pollReferenceRequests()`；断线指数退避重连（1s→60s）；**连不上时 60 秒轮询照旧**，兜底行为与今天逐字相同。
- 在线判定：流在连 = 在线（替代 180 秒 touchDevice 窗口用于参考读取；transfer 的 180 秒窗口不动）。
- 旧云后端（端点 404）：桌面端进程内钉死不再尝试，同既有 404 惯例。
- nginx：两台 addin 的通用 `location /api/` 已是 `proxy_buffering off` + `proxy_read_timeout 3600s`，无需改动。

## 6. 桌面端项目文件按需读取（#718）

- 新表不加。云端内存登记 `ReferenceRequestStore`：`{requestId, userId, deviceId, kind(LIST|READ), projectKey, path?, locator?, future}`，TTL 60 秒。
- `ref_list(source=desktop)`：目标项目 = 当前会话项目的 `addin_project_link`（deviceId, projectKey）；未绑定时列用户在线设备的项目目录镜像（MobileProjectDir）供模型再选。发 LIST → nudge → 桌面端 `GET /api/mobile/ref/requests` 取件 → 走项目文件树（DB 登记条目，路径与名称）→ `POST /api/mobile/ref/{id}/result` 回传 JSON → 完成 future。
- `ref_read(desk:…)`：发 READ → 桌面端按路径解析项目文件 → 调既有 `DocumentTextService` 与 OCR 路径（与 `extract_file_text` 同一实现，抽成可注入的 `ProjectFileTextExtractor`，FileTools 与 MobileRelayClientService 共用）→ 按 locator 截取 → 回传文本（≤200k 字符，UTF-8，`text/plain`）。
- 不计费：不经 `TransferBillingClient`；与 PULL/PUSH 账目完全分离。
- 不落盘：结果只进 future，完成即丢；日志只记 requestId/长度/耗时，不记正文。
- 超时 60 秒（首轮含冷启动）；离线立即返回「设备《X》离线（最后在线 …），请打开桌面端或手动上传文件」。
- 路径越界防护：桌面端只接受项目根内相对路径，规范化后仍须在项目根内；拒绝 `..`、绝对路径、符号链接逃逸。

### 6.1 「帮我打开 C」
- 仅当 C 在**发起窗格同一台机器**的桌面项目时提供：云端无法判定同机，改由桌面端在 LIST 结果中带 `openable:true`（桌面端所在机器即持有该文件）；AI 调 `ref_open(ref)` → 桌面端用系统默认程序打开该文件（macOS `open`，Windows `ShellExecute`）。
- `ref_open` 只对 `desk:` 来源可用，只打开不修改；打开后提示用户在该文档里打开 AI WorkDeck 窗格。
- 同机判定的不完美（用户在另一台机器上的窗格发起）接受：文件会在桌面端那台机器上打开，提示文案写明机器名。

## 7. git 来源（#720）

### 7.1 官方案件库（case 实例）
- case 与 addin 是同一 jar 的两个实例，库与用户表分离；**同一人的跨实例身份键 = `AccountBinding.externalAccountId`**（官网账号 id，两边 awdk 登录都会写）。已核实（代码）：两实例的 accountId 都取自同一官网 `GET /api/account/me`，只要两边 `ai.account.base-url` 相同即一致。
- case 实例新增内部端点 `/api/internal/ref/{list,read}`：仅 127.0.0.1 可达（nginx `^~ /api/internal/` 返回 404 兜底，与 transfer 计费同款）+ 共享密钥头 `X-Internal-Secret`（env `AWD_REF_INTERNAL_SECRET`，未配恒 404）。参数带 `externalAccountId`，case 侧据此找本地用户并按既有成员权限（非客户角色可读）判定。
- 读取：JGit 读 `repos/project-{id}.git` 的 master HEAD（经既有 `blobAt` 50MB 闸），Office 格式经 `DocumentTextService` 抽文字。
- 国际站没有案件库：SG 实例不配该密钥，来源自动缺席。
- 北京 addin 与 case 同机（127.0.0.1:9797），若日后分机需改内网地址，写进部署文档。

### 7.2 GitHub / Gitee
- 新表 `addin_git_repo_link`：`id, userId, cloudProjectId, provider(github|gitee), owner, repo, branch, tokenEnc, tokenLast4, createdAt, lastOkAt, lastError`。
- 令牌加密：复用 `PlatformAiKeyCipher` 同款 AES-GCM，密钥 env `AWD_GIT_TOKEN_SECRET`（未配则关联功能不可用并明示）。明文只在调用瞬间解密，不回显、不写日志。
- 插件端：项目下拉旁「关联 git 仓库」入口（设置区），填仓库地址、分支、令牌；保存前服务端以一次只读 API 调用校验；可解除关联（删行）。
- 读：GitHub `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1`（list）与 `GET /repos/{o}/{r}/contents/{path}?ref=`（read，>1MB 走 blob API）；Gitee v5 对应接口。字节只在内存，Office 格式经 `DocumentTextService` 抽文字。单文件上限 50MB。
- 只读，不提交、不推送（D 决策）。
- 令牌失效（401/403）：记 `lastError`，工具返回「git 仓库授权失效，请在设置里重新填写令牌」。

## 8. 隐私与合规

`legal/PRIVACY.md` 增补：
- 跨窗格读写：文档内容经 AI WorkDeck 云后端在同一账号的窗格之间转发，仅驻留内存，不存储。
- 桌面端参考读取：桌面端应请求抽取项目文件文字经云后端转发给 AI，仅驻留内存，不存储、不计费。
- 新出站请求：云后端访问 api.github.com、gitee.com（仅在用户关联仓库后、仅读取用户指定仓库）；访问令牌加密存储、可随时解除。
- 桌面端新常连：与既有 addin 云后端同一主机，不新增出站主机。

新建源文件带 SPDX 双行头。

## 9. 错误处理汇总

| 情况 | AI 收到的文案（中英两版） |
|---|---|
| 目标窗格不在线 | 《B》的窗格没有连着，请在 B 里打开 AI WorkDeck 窗格 |
| Word 不支持修订 | 本机 Word 版本无法标记修订，已拒绝跨文档修改 |
| 按页读取不支持 | 本机 Word 不支持按页读取，可改用标题或关键词 |
| 桌面端离线 | 设备《X》离线（最后在线时间），请打开桌面端或手动上传文件 |
| 读取超时 | 桌面端 60 秒内未响应，可稍后重试 |
| 路径越界 / 文件不存在 | 项目里没有这个文件，请用 ref_list 重新查找 |
| git 授权失效 | git 仓库授权失效，请在设置里重新填写令牌 |
| 超过 50MB | 文件超过 50MB，暂不支持作为参考材料读取 |

工具输出永不为空串（`ToolExecutionResultMessage.ensureNotBlank` 地雷）。

## 10. 测试

后端（`mvn test`，JDK 21）：
- `PaneRegistryTest`：心跳/过期/bye/排除自身/会话切换重登记。
- `CrossPaneDispatchTest`：origin 载荷、目标离线立即失败、归属校验、超时。
- `ReferenceRequestStoreTest` + `MobileRefControllerTest`：LIST/READ 往返、TTL、不计费（断言 TransferBillingClient 零调用）、正文不入日志。
- `DesktopStreamTest`：单连接顶替、nudge、心跳。
- `MobileRelayClientRefTest`（桌面侧）：nudge 触发取件、路径越界拒绝、404 钉死、断线回落轮询。
- `CaseRefInternalControllerTest`：密钥缺失 404、非本机拒绝、成员权限、JGit 读 HEAD（临时仓库）。
- `GitProviderSourceTest`：桩 HTTP 服务器模拟 GitHub/Gitee、401 记 lastError、令牌不入日志。
- `GitTokenCipherTest`、`ContextAssemblerServiceTest` 末位规则中英对拍、`ToolRegistry` 可见性（ref_* 仅 OFFICE）。

插件（`node --test`）：`revisionLog.test.js`（记录/撤销/冲突/上限）、`paneHeartbeat.test.js`、`i18n.test.js` 扫描新组件。

真机走查（截图入卡）：
1. Mac：Word A + Word B，A 读 B 第 N 页改 A；A 改 B，B 出现修订与修订记录条目。
2. Mac：Word A + PowerPoint B / Excel B，A 改 B，B 记录前后值并可撤销。
3. WPS（Parallels）：同 1、2。
4. 桌面端常连：发起到拿到正文的耗时（冷/热），断网回落。
5. 案件库项目与 GitHub 测试仓库各读一份 docx。

## 11. 发布面

- 云后端 jar（北京、新加坡）：新端点与登记簿；case 实例：内部端点 + env。
- 桌面端（随发版）：常连、READ/LIST 处理、ref_open。
- 插件静态包（两台）：心跳、跨窗格执行、修订记录面板、git 关联入口。
- nginx：addin 无需改；case.aiworkdeck.com 新增 `location ^~ /api/internal/ { return 404; }`（本仓尚无入站 internal 端点，属新增）。
- 领域文档同 PR 更新：office-addin.md、ai-chat.md、mobile-sync.md、version-control.md。

## 12. 未验证项（计划阶段首先核实）

1. Mac Word / WPS 文字的按页读取 API 可用性。
2. ~~addin 与 case 两实例 `externalAccountId` 一致~~（已由代码核实）。
3. Office 窗格卸载时 sendBeacon 在 Mac WKWebView 上是否送达（不送达时靠 90 秒过期兜底）。
4. 两个同宿主窗格（两个 Word 文档）各自独立 SSE 连接、sessionStorage 独立（paneId 不冲突）。
