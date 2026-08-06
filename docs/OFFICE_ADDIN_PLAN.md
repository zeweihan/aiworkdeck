# WorkDeck Office 插件（Word/Excel/PPT）调研与规划

日期：2026-08-06。状态：调研定稿，待立项。

三条调研线：微软官方文档（Office Add-in 基建）、licensing-billing 领域（用户系统地基）、ai-chat 领域（记忆与上下文可共享性）。本文是三线汇总 + 架构建议 + 分期路线。

---

## 一、总结论

1. **做插件本身零门槛**：Office Add-in = XML manifest + 自托管 Web 页面（HTML/JS，可用 Vue），开发调试 sideload 即可，不需要任何微软账号和费用。技术栈与现有前端完全兼容。
2. **「云端账户打通共享上下文」的设想与现有 local-first 架构相逆**：官网云端只有账户/权益/钱包/订单，用户的项目、文件、AI 记忆全部在本机（H2 + 本地磁盘）。云端账户能回答「他是谁、他买了什么」，回答不了「他的数据在哪」。
3. **因此推荐架构是「本地直连为主、云端身份为辅」**：插件连本机桌面后端（127.0.0.1:5269）读上下文、跑 AI——数据全在那里，且记忆/上下文组装层对入口零依赖，天然共享；awdk_ 账户 Key 负责付费身份与权益验证。跨设备云端记忆同步是独立的大工程，且涉及法律行业数据上云的产品红线，单独决策。
4. **AI 侧架构惊人地顺**：编排器入口就是标准 HTTP/SSE；「工具由客户端执行、结果回传」在 EditorBridgeService（editor_command 桥）已是成熟模式，Office.js 工具集照抄即可；记忆表本来就有 user_id 维度。
5. **中国市场两个特殊性**：世纪互联版 Office 365 没有商店，唯一分发路径是管理员集中部署（正好契合律所 To-B 交付）；WPS 用自己的加载项体系（wpsjs），不兼容 Office.js，覆盖 WPS 需单独维护一层适配。

---

## 二、微软侧基础设施（调研线 1）

### 2.1 插件形态

- Office Add-in = **manifest（清单）+ 自托管 Web 应用**。Office 客户端用内嵌 WebView 加载我们的页面，页面通过 Office.js 读写文档。逻辑与数据全在我们自己手里，微软只做 manifest 分发。
- manifest 选型：**生产用传统 XML add-in only manifest**。新的 JSON unified manifest 对 Word/Excel/PPT 仍是 preview（仅 Outlook GA），且不支持永久授权版 Office。
- 托管硬性要求：页面必须 HTTPS（本机开发可自签）；图标 URI 必须可缓存；任何服务器都行，国内服务器不受限。

### 2.2 账号与费用

| 阶段 | 需要什么 |
|---|---|
| 开发/测试 | 无。sideload 免费，工具链 yo office / Office Add-ins Development Kit（VS Code） |
| 可选沙箱 | Microsoft 365 Developer Program 免费 E5 租户（有资格审核，需持续开发续期） |
| 公开上架 | Partner Center 公司账号（Entra ID 工作账户 + 公司法人信息），注册费已取消，审批需时，**要提前办** |

### 2.3 分发渠道

| 渠道 | 适用 | 要点 |
|---|---|---|
| Sideload | 开发测试 | 不可用于生产 |
| **Microsoft 365 admin center 集中部署** | 律所/企业客户 | 管理员上传 manifest 即全员可用，不经微软审核，支持私有交付节奏；**世纪互联租户唯一生产路径** |
| Marketplace（原 AppSource） | 公开分发 | 需 Partner Center + 认证审核；只能免费上架，收费走自有订阅（与解锁门/账户 Key 体系兼容）；中国大陆用户无法在商店付费 |

- 世纪互联版必须改用中国 CDN 的 office.js：`https://appsforoffice.cdn.partner.office365.cn/appsforoffice/lib/1/hosted/office.js`。
- 自有账户登录是官方支持路径：任务窗格是 iframe，登录页必须用 **Office dialog API** 弹独立窗口，走 OAuth/自有流程后 `messageParent` 回传。这是最主要的平台坑。

### 2.4 WPS

WPS 用自己的「WPS 加载项」体系（wpsjs/jsapi），与 Office.js **不兼容**。业务逻辑、后端、AI 全可复用，需要单独维护的是文档操作 API 适配层和 manifest/分发。国内律师 WPS 占比高，建议列为 Phase 2 而非放弃。

---

## 三、用户系统地基评估（调研线 2）

### 3.1 现状：三套互不打通的身份

| 身份 | 位置 | 说明 |
|---|---|---|
| 桌面本机身份 | 本机 H2 user 表 | local-mode 免登，`AuthController.getUserIdFromSession` 对任何请求解析为本机用户（backend/src/main/java/com/checkba/controller/AuthController.java:225-243） |
| 官网云端账户 | 官网 SQLite | 有持久 userId + awd_session + api_keys/entitlements/wallet_ledger 全带 userId 维度；桌面端只以 `awdk_` Key 接触它（AccountService.java:68-88），本地不存官网 userId，**两套 ID 零映射** |
| 团队服务器身份 | 自建 server 模式实例 | `awdt_` 设备令牌（DeviceTokenService.java），version-control v2 协作通道，与官网无关 |

### 3.2 关键事实

- **官网 API 是无状态 Bearer Key 鉴权**，天然适配多客户端：插件持 Key 即可调 `/api/account/me` + `/api/account/entitlements` 验证付费身份。缺的是：体面的授权流程（现在只能官网手动生成再粘贴）、scope（Key 是全权的）、每账户 3 把 Key 的上限、官网 API 对插件域的 CORS。
- **用户数据几乎全部在本机**：H2（`~/.aiworkdeck/local`）+ 本地文件系统。官网不存任何项目数据、文件或 AI 记忆。这是 2026-08 商业化改造刻意选择的 local-first 架构。
- **本机后端无鉴权，防线在网络层**：LocalModeAccessFilter 三条闸门（回环地址、反代痕迹头 403、非白名单 Origin 的 POST 403，LocalModeAccessFilter.java:112-130）。把插件公网域名直接加 CORS 白名单等于给该域下所有页面开免鉴权全权访问本机后端——是明确的安全倒退（F1 攻击面），**必须配套配对凭据机制**。
- 端口：打包态 5269（降级链 5269→5369→5169），dev 9696。插件需做端口探测或发现文件。

### 3.3 需要补的地基（本地直连 MVP 路线）

| # | 项 | 档位 |
|---|---|---|
| 1 | 本地后端客户端配对凭据：复用 DeviceTokenService 模式（`awdt_` 哈希入库 + 桌面弹窗确认配对），LocalModeAccessFilter 为有效 token 增加放行分支；错误文案不得含「登录/未授权/请先」 | 中 |
| 2 | CORS + 过滤器规则：持配对 token 的插件 Origin 回 ACAO；GET 也应要求 token | 小 |
| 3 | 端口发现机制 | 小 |
| 4 | 面向插件的只读、范围受限 API 子集（项目列表/文件文本/记忆查询），不暴露全部 controller | 中 |
| 5 | HTTPS 任务窗格 → http://127.0.0.1 的 mixed-content 实测与方案 | 小-中 |

云端付费身份路线补充：官网 API 对插件域 CORS（改官网仓，须同步 `doc/desktop-contract.md` + `contract-check.mts`）；Key 加客户端类型标注或短期 token 签发（中）。锦上添花：OAuth2 授权码/设备码 + scope（中-大）；云端记忆/上下文同步服务（**大**，产品决策）；官网按用户 entitlement check 端点（小）。

---

## 四、AI 记忆与上下文可共享性评估（调研线 3）

### 4.1 记忆系统：共享友好

- 五作用域（user/project/conversation/file/global）持久化在主 DB（MemoryEntry.java:250-263），**memory_entry 本来就有 user_id 列**；向量索引可在任何进程重建（桌面版本就是 InMemory 不落盘）。
- 桌面免登模式下所有记忆挂在「本机用户」一个 ID 下——**插件连同一个本机后端即天然共享记忆，零改动**。
- 证据账本（PR#155）是注入时格式化层不是存储层，共享不受影响。

### 4.2 上下文组装：入参无桌面依赖

`ContextAssemblerService.assemble()` 入参全是普通值，复用前提只有三个：文件要在项目文件库有 fileId（或给 ContextItem 加内联正文支持）、要有 projectId（读权限校验）、要有 userId（本机免登自动解析）。满足后记忆注入零改动复用。

### 4.3 编排器入口：标准 HTTP/SSE，可直连

- `GET /api/agent/connect/{cid}`（SseEmitter）+ `POST /api/agent/chat`，前端用 fetch+ReadableStream 消费，Office WebView 完全具备同等能力。
- 障碍即 3.2/3.3 所列（鉴权、CORS/LocalModeAccessFilter、mixed content）；同一 conversationId 只允许一条 SSE 连接，插件应开自己的会话。

### 4.4 Office 工具桥：照抄 EditorBridgeService

- 现有「工具在客户端执行」成熟先例：EditorBridgeService（requestId + CompletableFuture + SSE 下发 client_action + `POST /api/ai/agent/editor-result` 回传 + 30s 超时），32 个 doc_* 工具全走它。
- 方案：新建 OfficeBridgeService（不复用 LOWA 契约的超时/白名单/双轨旧名）+ 一套 `office_*` 工具（AgentToolComponent + @Tool 自动注册，不改编排器）+ 插件端 Office.js 执行器（Word.run 落 insert/replace/comment/track changes）。
- **必须做会话级工具过滤**：按客户端能力（LOWA vs Office）过滤 ToolRegistry 三个消费点（getAllSpecifications/execute/resolve），防 doc_* 在插件会话空转超时。前车之鉴：PptxEditTools 因前端不实现对应 client_action 变成死路径被整体删除。

### 4.5 AI 侧需要补的

必须有：来源准入（M1，小-中，安全评审是主要成本）、会话/项目落点方案（M2，小-中）、活跃文档内联正文注入（M3，小-中）、Office 工具桥（M4，中，插件端 Office.js 语义映射是大头）、会话级工具过滤（M5，小-中）。
锦上添花：跨设备记忆同步（大）、插件会话 SSE 事件裁剪（小）、Word 原生修订与 redline 语义对齐（中）、平台计费展示（小）。

---

## 五、产品方案（对原始设想的完善）

原始设想：「用户用了官方云服务，在 A.docx 里打开插件时允许读取其他项目/文件的上下文并共享记忆」。完善为：

1. **身份走云端，数据走本机**。插件用 awdk_ Key（或配对流程简化版）验证「同一个付费用户」；上下文和记忆从本机桌面后端读——v1 的「共享」定义为**同一台机器上 Word/Excel/PPT 插件与桌面 App 共享全部项目上下文与记忆**（连同一后端，天然成立）。跨设备共享（办公室台式机写的记忆在家里笔记本的 Word 里可用）依赖云端数据面，列为独立决策项。
2. **插件是桌面 App 的伴生入口，不是替代品**。v1 明确依赖桌面应用在运行（数据和 AI 都在那）。插件价值主张：律师在真 Word 的完整排版/修订/协作环境里工作时，随手拉开任务窗格就能带着全部项目上下文问 AI、让 AI 直接改当前文档（走 Word 原生 track changes，AI 署名）。
3. **核心用户流**：打开 A.docx → 任务窗格自动识别本机 WorkDeck（端口探测 + 一次性配对确认）→ 可选关联到某个项目（或自动匹配：文档路径在某项目 localRoot 下时自动挂载）→ 对话时 AI 同时看得见 A.docx 正文（Office.js 读取内联注入）+ 该项目其他文件 + 项目记忆 + 用户偏好 → AI 编辑指令经 office_* 工具桥落到 Word 修订。
4. **变现**：插件免费分发（商店只能免费），AI 能力消耗与桌面端同一套 entitlement/平台计费额度。付费用户身份由账户 Key 打通。

## 六、分期路线

| 期 | 内容 | 前置 |
|---|---|---|
| Phase 0 原型（下个版本内） | Word 任务窗格（Vue + XML manifest + sideload）；本机配对凭据 + CORS/过滤器放行 + 端口发现；只读上下文 API 子集；复用 SSE 对话；A.docx 正文内联注入 | 无外部依赖 |
| Phase 1 可交付 | office_* 工具桥（Word 编辑落 track changes）+ 会话级工具过滤；Excel/PPT 宿主扩展（manifest 加 Hosts 即可，工具语义分批）；awdk_ 付费身份接入；律所集中部署交付文档 | HTTPS 托管域名 |
| Phase 2 生态 | Marketplace 免费上架（提前办 Partner Center）；WPS 加载项适配层；世纪互联 CDN 变体 | Partner Center 账号（审批期长，Phase 0 就该启动申请） |
| Phase 3 决策项 | 云端记忆/上下文同步（跨设备共享）；OAuth2 授权流程 + scope | 产品决策：法律行业数据上云红线 |

## 七、风险与决策点

1. **本机后端攻击面**：给插件开的每一条口子都在削弱 2026-08 安全审计建立的 F1 防线，配对凭据机制是不可省略的前置，需安全评审。
2. **插件依赖桌面 App 运行**：v1 形态是否可接受（未运行时任务窗格给「启动 WorkDeck」引导）。
3. **WPS 优先级**：国内律师 WPS 占比高，是否提前到 Phase 1。
4. **云端数据面**：跨设备共享记忆 = 用户数据上云，与 local-first 卖点冲突，需明确产品决策后再立项（可选折中：只同步 user 作用域的偏好类记忆，不动项目/文件数据）。
5. **Key 上限**：每账户 3 把 awdk_ Key，多客户端时代需要放宽或改造。

## 八、参考

- Office Add-ins overview: https://learn.microsoft.com/office/dev/add-ins/overview/office-add-ins
- Manifest: https://learn.microsoft.com/office/dev/add-ins/develop/add-in-manifests
- Publish/AppSource: https://learn.microsoft.com/office/dev/add-ins/publish/publish
- 主权云（含世纪互联）: https://learn.microsoft.com/office/dev/add-ins/publish/government-cloud-guidance
- 非微软身份鉴权: https://learn.microsoft.com/office/dev/add-ins/develop/auth-external-add-ins
- Office dialog API 登录: https://learn.microsoft.com/office/dev/add-ins/develop/auth-with-office-dialog-api
- WPS 加载项开发说明: https://qn.cache.wpscdn.cn/encs/doc/office_v8/topics/WPS%20%E5%8A%A0%E8%BD%BD%E9%A1%B9%E5%BC%80%E5%8F%91/WPS%20%E5%8A%A0%E8%BD%BD%E9%A1%B9%E5%BC%80%E5%8F%91%E8%AF%B4%E6%98%8E.html
