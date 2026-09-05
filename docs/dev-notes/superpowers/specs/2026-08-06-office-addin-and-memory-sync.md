# Spec：Office 插件（独立运行）+ 记忆/上下文 Git 同步体系

日期：2026-08-06。状态：定稿，分期实施中。
前置调研：`docs/OFFICE_ADDIN_PLAN.md`（三线调研）+ 本 spec 立项时的两路补充调研（Git 载体可行性、server 模式就绪度），结论已合入本文。

## 一、决策记录

| # | 决策 | 影响 |
|---|---|---|
| D1 | 插件必须可**独立运行**（不依赖同机桌面 App） | 插件的一等后端是云端实例（官方云或律所自建服务器）；同机桌面后端是可选的加速/离线路径。推翻调研 v1 的「本地直连为主」 |
| D2 | 不做 WPS 加载项 | 只维护 Office.js 一套 |
| D3 | 云端记忆立项，载体 = **Git**：官方 Git 服务器（现有 GitHttpController）或用户自填标准 Git remote（律所自建 gitea/gitlab 等） | 数据仍在用户/律所掌控下，local-first 卖点保留；不新建私有同步协议 |

## 二、架构总览

```
Office (Word/Excel/PPT)
  └─ 任务窗格插件（office-addin/，Vue3 + Office.js）
       │  HTTPS + awdt_ 设备令牌（MVP）/ awdk_ 桥（Phase D）
       ▼
后端实例（同一套 Spring 代码的三种部署形态）
  本机桌面(5269) / 律所团队服务器 / 官方云
       │  记忆读写走既有编排器与 DB（插件对 Git 无感知）
       ▼
记忆 Git 仓库（独立于项目文档仓库）
  repos/user-{uid}-memory.git      ← user/global 作用域
  repos/project-{id}-memory.git    ← project/file/conversation 作用域
       │  标准 smart HTTP（JGit），后端之间互同步
       ▼
官方 Git 服务器 或 用户自填任意标准 Git remote
```

关键架构事实（调研实证）：
- 现有 Git 传输层就是标准 smart HTTP（GitHttpController 三端点 + JGit UploadPack/ReceivePack），指向标准 Git 服务技术上可通；「自填 remote」是流程剪裁不是协议重写。
- 记忆全在 DB（memory_entry 有 user_id 维度），向量索引可随处重建；Git 只做后端间同步载体。
- `awdt_` 设备令牌（哈希落库、长期、可吊销、`getUserIdFromSession` 已支持前缀解析）是插件鉴权的现成形态，不依赖内存会话。
- server 模式鉴权经 2026-08 审计（PR#241），AI 端点隔离可信；差距集中在账户体系（内网假设）与计费（单机假设）。

## 三、Phase A：记忆 Git 同步层（后端）

### 设计

**红线：记忆用独立仓库，绝不进项目文档仓库主线。**（否则：退回版本会连记忆一起退回；AI 落记忆弄脏律师时间线/触发幽灵工作段；MERGING 裁决窗口冻结记忆同步；采纳并集语义需全部重新论证。）

1. **仓库拓扑**：`repos/user-{userId}-memory.git`（owner-only）+ `repos/project-{id}-memory.git`（复用项目成员权限）。与项目仓库同款「裸库 + workTree 物化」布局可简化为纯裸库 + 临时工作树，实施者按最小改动选择。
2. **文件格式**：一条记忆一个文件 `{scope}/{uid}.md`——YAML front-matter（memoryType/importance/updatedAt/tombstone/sourceFileUid 等结构字段）+ 正文（memoryValue）。add/add 永不冲突；git 历史 = 记忆审计轨迹。
3. **身份**：跨机器只认 uid（存量 memory_entry 补 UUID 列，仿清单 v2 回填）；本机数字 id 永远是派生值（地雷 #27 教训）。删除用墓碑字段不删文件（防陈旧端复活）。向量嵌入不进 Git，拉取后本地重算。
4. **同步编排**：
   - 写侧：`MemoryPipelineService.onConversationTurnCompleted` 后防抖导出 + commit + push；push 被拒 → fetch → 自动合并 → 重推；网络失败置 pendingUpload 绝不阻断（骨架照抄 `CloudSyncService.uploadToCloud`/`integrateFromCloud`）。
   - 读侧：120s + focus 轮询 fetch（PR-E 三守卫模式）；上下文组装前可选轻量 fetch。
5. **冲突策略**：全自动、无 MERGING 停留窗口、无 UI。快进优先；真合并逐文件：单边改取该边；双边改同 uid 按 front-matter `updatedAt` LWW；墓碑 vs 编辑 → 墓碑胜。合并后按 uid 差异回灌 DB（新建/更新/软删）。
6. **服务端**：GitHttpController 路由支持 memory 仓库键；GitAccessService 加 user 仓 owner-only 规则；per-repoKey 锁替代 per-projectId 锁。
7. **自填 remote 模式**：记忆仓库的 remote 允许用户直填任意标准 Git URL + 凭据（本地保存），只用 clone/fetch/push 三操作，跳过 prepare-remote/建项目/成员流程。

### 复用清单（实施入口）

`ProjectRepoService`：setRemoteOrigin(:683)/fetchFromOrigin(:713)/pushMainlineToOrigin(:765, PushOutcome 返回值模式)/cloneFromRemote(:878)/fastForwardMainline(:802)/mergeNoCommit/abortMerge/commitMergeResolution——gitDir 从 projectId 泛化为 repoKey。`DeviceTokenService` 原样。`GitAccessService.authorize` 加规则。`CloudSyncService` 的重试/离线纪律照抄。

### 验证标准
- mvn test 新增：导出/回灌 round-trip、LWW 合并矩阵（含墓碑防复活）、uid 回填幂等。
- 双实例集成测试：A 写记忆 → push → B fetch 回灌 → B 检索命中；并发双写 → 自动合并无窗口。
- 既有 version 包测试全绿（证明项目仓库契约零干扰）。

## 四、Phase B：Office 插件脚手架（Word 先行）

### 设计

1. 新目录 `office-addin/`：`manifest.xml`（XML add-in only 格式，Hosts=Document，后续加 Workbook/Presentation）+ `taskpane/`（Vue3 + Vite，npm）。office.js 从微软 CDN 引（世纪互联变体后置）。
2. **连接配置**：设置页填后端地址（默认官方云占位，可填律所服务器/`http://127.0.0.1:5269`）+ awdt_ 设备令牌粘贴（MVP；awdk_ 桥在 Phase D 替换为体面流程）。令牌存 Office.js roamingSettings 或 localStorage。
3. **对话**：复用 `/api/agent/connect/{cid}`（SSE）+ `/api/agent/chat`。conversationId 插件自造（`conv-` 前缀不变）；插件会话独立，不与桌面 App 共用。
4. **上下文注入**：Office.js 读当前文档正文（Word.run → body.text），随 /chat 以内联正文形式传（后端给 ContextItem/activeContext 加 `inlineContent` 字段支持，AiAgentController 透传给 ContextAssemblerService；末位 `[系统提醒]` 文案同步）。项目关联：MVP 提供项目下拉选择（`/api/projects/my`）。
5. **后端配套小改**：activeContext 内联正文（上一条）；CORS 文档化（插件 Origin 进 `security.cors.allowed-origins` 部署配置，绝不开 allow-all）。
6. sideload 调试链路文档（`office-addin/README.md`）。

### 验证标准
- `npm run build` 通过；manifest 过 `office-addin-manifest validate`。
- 真机手测清单：sideload 进 Word → 配置服务器 + 令牌 → 选项目 → 发消息收到 SSE 流式回复 → 回复中引用了当前文档内容与项目记忆。
- 后端 mvn test 全绿（inlineContent 路径有单测）。

## 五、Phase C：office_* 工具桥 + 会话级能力过滤

### 设计

1. **OfficeBridgeService 新建**（不复用 EditorBridgeService 的 LOWA 契约/超时语义/双轨旧名，但逐字同构：requestId + CompletableFuture + SSE `client_action` 下发 + 结果回传端点 + 超时）。
2. **office_* 工具集 v1**（AgentToolComponent + @Tool + @ToolMeta 自动注册，不改编排器）：office_get_text / office_get_selection / office_replace_text（走 Word 原生 track changes，修订署名对齐 __agent 约定）/ office_insert_text / office_add_comment / office_search。Excel/PPT 语义后续版本。
3. **会话级客户端能力**：SSE connect 或 chat 请求声明 `clientCapability`（lowa/office/none），ToolRegistry 三个消费点（getAllSpecifications/execute/resolve）按能力过滤——office 会话不见 doc_*，LOWA 会话不见 office_*，杜绝 30s 超时空转（PptxEditTools 死路径教训）。
4. 插件端执行器：消费 `client_action`（tool=office_command）→ Office.js 执行 → POST 结果回传。
5. **地雷**：若动编排器构造器必须同步 EvalHarness（已两次踩坑）；工具名中文映射表要加 office_* 条目。

### 验证标准
- mvn test：桥的请求-应答/超时/错误 JSON 单测；能力过滤三消费点单测。
- 真机手测：Word 里让 AI 改一段文字 → 文档出现带 AI 署名的修订；插件会话中 AI 不再尝试调 doc_*。
- EvalHarness 编译通过、回放评测不回归。

## 六、Phase D：云后端生产化（官方托管 + awdk_ 桥）

按 server 模式就绪度调研的差距清单执行（档位）：
1. awdk_ → server 会话桥：新匿名端点 `POST /api/auth/awdk-login`（调官网 `GET /api/account/me` 校验）+ 官网补稳定 accountId 字段（**跨仓**：官网仓改动须同步 `doc/desktop-contract.md` + `scripts/contract-check.mts`）+ `account_binding` 映射表 + 无密码建号路径；关闭/闸住开放注册（中）。
2. 会话生产化：插件端一律用 awdt_ 形态持久凭据，弃内存 SESSION_STORE 依赖（小）。
3. 登录/注册防滥用：nginx limit_req + 失败锁定起步（小）。
4. 平台 AI 通道按用户化：AccountService/PlatformAiChannel 从机器级文件改 per-user DB 记录，每用户一把 provisioned key（官网 `POST /api/account/ai-key` 现成、幂等）；PlatformUsageAccountant 串位问题随之消解（中）。
5. AccountController/EntitlementController 的 server 语义封堵（requireAdmin 或随 #4 按用户化）（小）。
6. conversationId 服务端签发（空会话抢占窗口）（小-中）。
7. 运维基线：后端 Dockerfile、DB 备份、PG（pgvector）生产化——prod profile 用 MySQL 会致向量库降级内存（中）。

失败文案红线：不得含「登录/未授权/请先」子串（licensing 领域地雷 1）。

### 验证标准
- 桥的双向集成测试（官网 mock）；契约护栏 contract-check 通过；公网实例冒烟（注册闸、限流、per-user key 各一条）。

## 七、分期依赖与交付

```
Phase A（记忆 Git 同步）──┐
Phase B（插件脚手架）  ──┴→ Phase C（工具桥+能力过滤）→ Phase D（云后端生产化）→ 发版
```
A 与 B 无依赖可并行；每 Phase 独立 PR 交付，PR 合并时更新对应领域文档（version-control.md / 新增 office-addin 说明 / ai-doc-bridge.md / licensing-billing.md）。

## 八、传给实施者的地雷清单

- 本机 mvn 必须 JDK 21（默认 25 SIGBUS），JAVA_HOME 显式传。
- 前端/插件包管理用 npm，不用 pnpm。
- 全局禁 emoji（代码/UI/文档/commit）。
- docs/ 在 .gitignore，入库要 `git add -f`。
- worktree 编辑与构建必须同树。
- 改编排器构造器必须同步 EvalHarness。
- 注册远端执行工具前必须确保客户端真的实现了对应 action（PptxEditTools 死路径教训）。
- `security.cors.allow-all` 逃生门绝不能开。
- 记忆不进项目文档仓库（本 spec 红线，见 Phase A）。
