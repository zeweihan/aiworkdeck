# 项目概念回归与项目概览页 设计文档

日期：2026-08-08
状态：待实施
分期：A 期（本文档全部内容）→ B 期（任务与日程系统，另出 spec）

事实基础：一次六路并行的代码调研（路由 / 项目档案 / 动态统计 / 对话历史 / Git 仓库化 / 任务选型），每路经一轮独立对抗性核查逐条打开行号证伪，共 208 条事实、6 组核查、23 条被证伪的结论。**证伪清单见 §12，不要从旧笔记里把它们捡回来。**

---

## 0. 术语表（第一段就要定，否则全篇歧义）

仓库里已经有一个叫 `project-overview` 的东西，**它是工作台**。`pages.json:41` 的中文标题恰好也叫「项目概览」，`frontend/tests/app-e2e/run.mjs:321` 用 `hash.includes('project-overview')` 判定「进了工作台」，`.claude/agents/sidebar-shell.md:10-12` 称它「主战场」。

| 术语 | 指代 | 路由 |
|---|---|---|
| **工作台** | 现有四列布局的干活界面 | `pages/project-overview/project-overview`（**不改名**） |
| **项目列表页** | 从个人中心搬出来的独立页面 | `pages/project-list/project-list`（新增） |
| **项目概览页** | 本文档要做的 General Overview | `pages/project-home/project-home`（新增） |

**关于要不要顺手把工作台改名 `workbench`**：调研的综合建议是改（语义永久干净），我的决定是**不改**。理由——改名要动 9 处硬编码 URL（`pages.json:39`、`App.vue:45`、`launch.vue:97`、`login.vue:297`、`login.vue:408`、`newproject/index.vue:234`、`variable-library.vue:321`、`project-overview.vue:2681`、`userprofile.vue:1214`）、移动 `pages/project-overview/` 下 11 个模块文件、改 e2e 断言、改三份领域文档，还会在埋点里造成一次 path 维度的历史断裂（路由埋点是 `App.vue:15-26` 的拦截器按 path 上报）。而这个 PR 本身已经背着「默认建仓」这种高风险改动，**不要把机械重命名叠在高风险 PR 上**。

代价是「project-overview」在代码里指工作台、在产品语言里指新页。缓解：本术语表同步写进 `CLAUDE.md` 与 `sidebar-shell.md`，不只留在本 spec 里。改名单列为一次可选的独立清理。

---

## 1. 背景与目标

系统最初有「项目」概念，后来为对齐 IDE 形态在 UI 上弱化了——**弱化只发生在前端**，后端 `Project` 一直是一等公民。现状：项目列表寄居在 `userprofile.vue` 的一个 tab 里（`activeTab` 默认值就是 `'projects'`，`:492`）；没有任何页面回答「这个项目现在是什么状态」；概览所需的数据大部分已经躺在库里没有出口。

**方案 C（已定）**：项目列表页 → 项目概览页 → 工作台三级。`launch` 启动直达上次工作台的行为保留，只有主动切项目时才经过概览。

---

## 2. 范围与分期

| 期 | 内容 |
|---|---|
| **A** | 项目定义收紧（默认建仓）、项目档案（AI 抽取 + 用户手填）、项目列表页、项目概览页、动态与统计、AI 对话历史铺开 |
| **B** | 任务与日程系统、日历视图、任务↔文档双向打通、外部日历双向同步 |

A 期就把 `GET /api/projects/{id}/tasks` 端点落地并返回空数组、概览页把「日程与任务」块渲染成空态。**B 期接任务系统时概览页一行不用改。**

外部日历双向同步（维护者已明确要）在 B 期做，provider 选型（飞书/企业微信/Exchange/CalDAV）另议——牵动用户数据出境判断。

---

## 3. 项目定义：默认建仓

### 3.1 准确表述（产品文案与领域文档都要改口径）

「一个项目就是一个 Git 仓库」需要两处校正：

**其一，`.git` 不在项目文件夹里**——它在 `{globalRoot}/repos/project-{id}.git`（`ProjectRepoService.gitDir()/workTree()`），工作树才是律师的文件夹。数据库文件树才是真源，磁盘只是它的物化，因此有 `.awd/tree.json` 跟着每一笔提交走。维护者已确认接受（2026-08-08）。

**其二，仓库只装文件字节，不装项目。** 仓库**不包含**：`Project` 行（元数据）、`ProjectAiMessage`（对话历史）、`WorkSession`（工作段/稿的业务状态）、`MemoryEntry`（记忆，另有独立仓库）、`ProjectMember`/`ProjectRemote`/`CloudConnection`（成员与协作绑定）。`revertTo` 只改磁盘文件 + 把清单同步回数据库，**不回滚对话、任务、记忆**。

准确说法：**每个项目的文件都有一份自动维护的本地版本仓库。** spec 与 UI 都不要写「概览页读项目仓库」。

### 3.2 现状

opt-in：`enabled = repoService.isInitialized(projectId)`（`VersionController.java:62`），生产代码唯一调用点是 `POST /version/enable`（`VersionController.java:199-207`，`:203 requireWriteMember` 拒 READ_ONLY/CLIENT）→ `WorkSessionService.enableVersionRecording:109-119`（`:113 isInitialized` 幂等短路，回归测试钉在 `WorkSessionServiceTest.java:520-528`）。前端唯一入口 `VersionPanel.vue:10-16`。

**但仓库还有两条不经 `enableVersionRecording` 的诞生路径**：`POST /prepare-remote` → `initEmptyForReceive`（`VersionController.java:214-228`）、从团队案件库接入 → `cloneFromCloud` 整仓克隆（`CloudSyncService.java:236-245`，克隆下来的项目一落地就是「已开」）。

**opt-in 的真正边界是 `WorkSessionService.java:201` 的 `if (!repoService.isInitialized(projectId)) return;`**（`onChangeSignal` 第一行）。

**本机迁移规模样本**（2026-08-08 实测）：`~/.aiworkdeck/data/projects` 下 21 个托管项目共 93MB（单个最大 16MB、文件数个位数），`data/repos` 下只有 1 个仓 40KB。**托管项目迁移代价可忽略，风险全部集中在 localRoot 项目。**

### 3.3 决策：默认开启 + 四道闸门 + 分档迁移

**触发时点：每次打开项目时懒补**（靠 `isInitialized` 幂等，无需 migrated 标记，照抄 `prepare-remote` 的懒升级形制 `VersionController.java:208-227`——那是本领域已验证过的迁移形制）。**不放在 `ProjectService.createProject` 里**（见闸门 4）。

**分档**：托管项目静默补齐；`localRoot` 项目走「体量闸门 + 可见提示」。

#### 闸门 1：体量预检

今天最危险的路径：律师打开一个塞着 node_modules / 视频 / 外置盘的文件夹，**仅仅是 `onLoad` 拉一次 `/status`** 就把整个文件夹哈希写进对象库——磁盘瞬间翻倍、UI 卡在 per-project 锁上，用户什么都没做（`VersionController.java:68` → `WorkSessionService.java:340-348` → `ProjectRepoService.java:241-242`）。

选文件夹时**没有任何体积拦截点可复用**：`validateLocalRoot`（`LocalProjectService.java:209-236`）五条校验全是路径合法性（非空/绝对路径/非磁盘根/不在数据根内/必须存在且是目录），`rejectNestedRoot`（`:239-248`）**只对新建项目生效**，重开已存在的 localRoot 完全跳过。

建仓前对工作区做体量预检（文件数 / 总字节）。超阈值**不默认建**，概览页给显式提示 + 「仍然开启」按钮。文件数阈值与导入上限同源（`MAX_IMPORT_ENTRIES=3000`，`LocalProjectService.java:40-41`），字节阈值待标定（见 §11 Q1）。**托管项目不做预检。**

#### 闸门 2：收录范围与文件树同源

数据库导入有 3000 条上限、20 层深度、跳过点开头目录（`LocalProjectService.java:285/290/291-294/325`），`git add "."`（`ProjectRepoService.java:127-128`）不受任何约束。

注意：`ProjectStorageResolver.java:28-29` 那条「git 提交的内容 = 用户看到的文件」契约**在 localRoot 项目上今天就已经破了**，默认开启只是把破口推广到全部项目。

**实测结论**（2026-08-08，JGit 6.9.0.202403050737-r，护栏测试 `backend/src/test/java/com/checkba/version/JGitAddBehaviorProbeTest.java`，2 个用例全绿）：

| 行为 | 实测结果 | 对设计的影响 |
|---|---|---|
| 工作区自带 `.gitignore` | **生效**（`app.log`、`node_modules/pkg/index.js` 都没进树） | 排除规则可行，**不需要**把 `addFilepattern(".")` 改成显式路径集合 |
| 嵌套 `.git` 目录 | **存成 `GITLINK(160000)`**，内容不展开，`.git` 内部不泄漏 | 风险远小于预期。但见下方语义说明 |
| 符号链接（指向目录 / 指向文件） | **都存成 `SYMLINK(120000)`，不跟进目标** | 外置盘、网络盘、大目录不会被顺着链接吞进来 |
| 点开头目录里的普通文件 | **被 git 收下**（`.venv/lib/site.py`、`.DS_Store` 都在树里） | 确认「文件树看不见但已被版本记录收录」成立，排除规则必须覆盖点开头目录 |
| `$GIT_DIR/info/exclude` | **生效，且与用户自带 `.gitignore` 叠加**（不互相顶掉） | **决定了下面的方案** |

**方案：排除规则写进 `{gitDir}/info/exclude`，不往律师的文件夹里写任何东西。**

gitDir 恒在 `{globalRoot}/repos/project-{id}.git`（工作区之外），所以这条规则对律师完全不可见，也不会与他自己的 `.gitignore` 互相覆盖——实测两者叠加生效。这比原计划的「写一份 `.gitignore` 进用户文件夹」严格更好：既守住了「用户自己的文件夹里不冒出他没写过的文件」这条既有承诺，又避免了「用户已有 `.gitignore` 时该追加还是覆盖」这个没有好答案的问题。

默认规则（与导入规则同源）：`.*/`（点开头目录，对齐 `LocalProjectService.java:290`）、`node_modules/`、`dist/`、`target/`、`.venv/`、`*.mp4` 等大二进制。

> **一条要写进领域文档的语义**：嵌套的 git 仓库存成 gitlink，意味着律师放在案卷里的某个 git 项目，**其内容不会进入版本记录的历史**——退回版本时那个子目录不受保护。这不是 bug（吞进去反而更糟），但要在 `.claude/agents/version-control.md` 里写明，否则将来会被当成数据丢失来查。

`.awd/` 目录仍会出现在用户文件夹里（清单必须与内容进同一笔提交），这一条不变，spec 明确接受。新口径写进 `.claude/agents/version-control.md` 作为契约修订。

#### 闸门 3：修 `isInitialized` 的半残仓陷阱

`ProjectRepoService.init:92-115` 里 `repo.create(true)`（`:101`）先跑，objects 目录已生成；之后 add/commit 抛异常时 `:112-114` 的 catch 只包异常不删目录，而 `isInitialized:75-77` 只看 objects 存在 → **下次 enable 直接短路 return，项目永久卡在「有仓但没有初始提交」且 UI 显示 enabled=true**。

修法二选一：`init` 的 catch 里删掉刚建的 gitDir 再抛；或把 `isInitialized` 改成「HEAD 能解析出提交」。**默认开启前必须先修。**

#### 闸门 4：触发点不进事务

`ProjectService.createProject` 是 `@Transactional`（`:36-37`）。默认建仓塞进去 = git IO 进数据库事务，失败连项目行一起回滚，直接违反「版本记录是保险、绝不阻断主流程」的硬纪律。

那条纪律的既有落点是三处 try/catch：`ProjectFileService.java:1193-1206`、`FileController.java:123-135`、`AgentOrchestrator.java:871-876`——**这三处只包住 `onChangeSignal`/`commitAiRound`，不覆盖 `init`**。新增的建仓触发点必须自己包一层，只 `log.warn`。

### 3.4 一个必须先想清楚的产品语义变化

默认开启后，每一次文件保存/新建/重命名/移动/删除（`signalChange` 调用点：`ProjectFileService.java:113/265/340/360/448/521/532/615` 与 `FileController.java:494`）都会走到 `:211 ensureSession`（`:313-320`）→ **隐式创建 `work/{millis}` 分支并 checkout 工作区**（对 localRoot 项目 = 在律师自己的文件夹里做 git checkout），`:217` 排 2 分钟防抖提交、`:222` 武装 30 分钟空闲定时器。

**后果：所有项目的顶栏工作状态点会常驻「工作中」**（`project-overview.vue:2731-2736`）。这个默认态怎么呈现必须先定——一个永远亮着的状态点等于没有状态点。

建议：工作状态点的显示条件从 `working` 改成 `working && changedCount > 0`，「有一段没收尾的工作」这件事本身不再单独提示（默认开启后它恒为真，不再是信息）。

> **未验证**：早退翻转（`isInitialized` 从「大多数项目走这条」变成「几乎不走」）对保存吞吐的实际影响没有测过。默认开启前需实测。

### 3.5 连带必修项

| # | 问题 | 证据 | 处理 |
|---|---|---|---|
| 1 | 删项目不删仓，且 GC job 只遍历现存项目 → 每删一个项目永久漏一个仓且永不 GC | `ProjectService.java:202-207`（只 `deleteById`）、`RepoMaintenanceJob.java:31-32` | 删项目时一并删仓（或移 `repos/trash` 保留 30 天）+ job 加「清理无对应项目行的孤儿仓」 |
| 2 | 删项目不清 AI 数据。**本机实测 198 条对话消息里 126 条的 project_id 已无对应 project 行** | 同上 | 同批清理 `project_ai_message`/`project_memory`/`conversation_summary`。**IDENTITY 主键可能复用，新项目会继承已删项目的档案** |
| 3 | GC 在桌面版基本不执行（固定 03:30 cron、无启动补跑）→ 松散对象只增不减 | `RepoMaintenanceJob.java:29-42`，version 包内无第二个 `@Scheduled` | 改成启动后延迟触发 + 空闲触发 |
| 4 | GC 工作量从少数项目变全部项目且串行，期间持有 repoLock，该项目的 /status 与自动存档一起阻塞 | `WorkSessionService.java:353-371` | `gcLocked` 加 `tryLock`（拿不到跳过）；只对超阈值的仓跑 |
| 5 | FSEvents 回弹：我们自己写 `.awd/tree.json` 触发一次全目录对账 | `LocalRootWatchService.java:41/:91-94`（listener 对 event 不做任何判断） | **watcher listener 过滤点开头路径段（至少 `.awd/`）——一行改动，收益直接** |
| 6 | 一次自动存档跑 **4 次** `git add "."` | `commitNow:475` → `describePendingChanges` → `:241-242` 两次；`:476 commitAll` → `:127-128` 两次 | 记入代价估算。可选优化：`commitAll` 改用显式路径集合替代 `addFilepattern(".")` |
| 7 | 没有关闭路径 | `VersionController` 全部端点无 disable/删仓接口 | A 期表态**不提供关闭**。`VersionPanel` 的 `enabled=false` 分支**保留但改语义**：从「还没开启，点这里开启」改成「这份案卷的版本记录不可用，点这里重建」（仓库被误删/迁移失败时这是唯一可见信号，`:181-186` 已刻意规定读取失败绝不落回引导态）。同步改 e2e J9（`run.mjs:479-489`） |
| 8 | `shareToCloud` 的「请先开启版本记录」分支变成不可达死路径 | `CloudSyncService.java:165-167` | 改成「版本记录不可用」的技术档 |
| 9 | 服务端 `/git/{projectId}.git` 从 404 变可用，暴露面按项目数放大 | `GitHttpController.java:208-217` | 端点可用性判据从 `isInitialized` 改成「已放进团队案件库（存在 `ProjectRemote`）」，本地建仓与云端可达解耦 |
| 10 | 记忆仓（`project-{id}-memory.git`）与文档仓同放 `repos/` 但永不 GC | `RepoMaintenanceJob.java:31-32` 只遍历项目 | 顺手纳入维护任务。**记忆仓是否默认开启是独立决定**（它需单独配 remote 才生效） |

`capture()` 的迁移注意：`ProjectTreeManifestService.java:55-85` 是 `@Transactional`，`:61-66` 对每一行缺 uid 的 `ProjectFile` 逐行 save（**N 次 UPDATE**）。批量迁移必须自己切事务边界。

---

## 4. 项目档案

### 4.1 载体：新表 + `.awd/profile.json` 进项目文档仓

**不复用 `project_memory`**，三条理由：

1. **消费者不同**。`project_memory` 服务的是 AI 上下文装配（`ContextAssemblerService.java:394/:452`、`MemoryTools.java:206`、`ContextCompressor.java:328`）——它是喂给模型的记忆。档案是给律师看、律师能改的字段。记忆错了模型会绕过去，档案错了律师会当真。
2. **写入是整行覆盖且无乐观锁**。`MemoryManager.saveProjectMemory:628-643` 只从 existing 抄回 id 和 createdAt，然后 save 整个游离实体；实体上 `@Version` 零命中。正则抽取器（每轮异步）与 `update_project_info` 工具（模型随时调）写同一行，**后到的整行覆盖会抹掉对方刚写的字段**。律师手填的值放进去必被覆盖。
3. **字段对不齐**。`project_memory` 15 列里没有「客户」（只有 listedCompany/targetCompany）、没有「成立时间」、没有「下一步」。

> 关于 `project_memory` 的五列（projectName/projectType/listedCompany/targetCompany/transactionStructure）：**有写入通道但完全靠模型自觉**——`MemoryManager.updateProjectField:649-672` 正是写这五个字段，唯一调用点 `MemoryTools.java:245` 的 `update_project_info` 工具。**本机实测 68 行里这些列非空计数均为 0。** 既不能说「没有通道」（会导致重复造轮子），也不能说「有数据可用」。

**新表 `project_profile_field`**（一个字段一行——字段级来源标记要求行级粒度）：

```
project_profile_field
  id             Long, IDENTITY
  projectId      Long, NOT NULL, 索引
  fieldKey       String(64)   client / matterType / openedAt / nextStep / counterparty
  fieldValue     String (TEXT)
  source         String(8)    'ai' | 'user'
  confidence     Double       AI 填时的置信度，user 填时 null
  evidence       String (TEXT) AI 是从哪份文件哪句话得出的
  uid            String(36)   UUID，跨机器身份
  updatedAt      LocalDateTime
  唯一约束 (projectId, fieldKey)
```

**核心规则：`source='user'` 的字段锁定，AI 永不覆盖。** AI 有新判断时另存一条 pending 建议挂旁边，律师一键采纳才写入。`source='ai'` 且未确认的字段，UI 必须弱化标记——律师不能把模型猜的立项日期当事实拿去跟客户对。

### 4.2 跨机器同步

普通表不进任何同步链：`project_memory` 无 uid，`MemoryRealm` 的 project 仓只同步 `memory_entry` 的 scope ∈ {project, file, conversation}，`MemorySyncService` 的合法路径正则只认 `uid.md`。档案落普通表 = 换机器就没了、同事取回案卷是空的。

**解法：DB 是真源，`.awd/profile.json` 是载体**，与 `.awd/tree.json` 完全同构——`ProjectProfileManifestService.capture()` 在既有的 `writeToWorkTree` 调用点（`WorkSessionService.java:114/397/474/502/640/819/1203/1390`、`CloudSyncService.java:688`）一起写；`applyToDatabase()` 差异同步回 DB，用于 `cloneFromCloud` 后与切线后；跨机器身份只认 `uid`。

**这条要正面回应记忆同步立过的红线。** `.claude/agents/version-control.md` 明写「记忆用独立 Git 仓库，绝不进项目文档仓库主线」，四条理由：退回版本连记忆一起退回、AI 落记忆弄脏时间线/孵幽灵工作段、MERGING 窗口冻结同步、采纳并集语义需重新论证。逐条对档案核算：

| 红线理由 | 对档案是否成立 | 处理 |
|---|---|---|
| 退回连记忆一起退回 | **成立且必须避免**——客户名不该因为回滚一份草稿而变回去 | `revertTo` 显式排除 `.awd/profile.json`（它本来就是 path-scoped 逐路径还原，加一条排除即可） |
| 弄脏时间线 | **不成立**——`describeChanges()` 已过滤 `.awd/` 前缀，`conflictingPaths` 也「一律过滤 `.awd/`」（`VersionController.java:109` 的「律师不可见铁律」）。既有机制已覆盖 | 无需额外处理 |
| 孵幽灵工作段 | **不成立**——profile.json 由我们自己的 capture 路径在提交前写，不经 `ProjectFileService.signalChange`，与 tree.json 同理 | 无需额外处理（FSEvents 回弹另见 §3.5 第 5 条） |
| MERGING 窗口冻结 | 成立但影响极小——裁决期间档案变更晚一点进历史，与 tree.json 同等待遇 | 接受 |
| 采纳并集语义需重新论证 | **成立，需要设计** | 每个字段自带 `updatedAt` → **按字段 LWW**，不需要冲突裁决 UI，不走 `unionApply` |

**档案与记忆的关键差别**，决定了它们该走不同的仓：记忆是高频 AI 写入、带向量嵌入、跨 user/global 作用域；档案是低频、项目独占、人可编辑，而且**必须恰好在案卷流转时一起流转**。放进项目文档仓，同步时机与「把案卷放进团队案件库」严格对齐；放进记忆仓则需要律师额外配一次记忆同步 remote，两个 opt-in 不重合，同事取回案卷照样拿不到档案。

副作用之一是好事：档案变更进版本记录，律师能看到「客户名是哪次工作里改的」。

### 4.3 字段集

| fieldKey | 显示 | 来源 |
|---|---|---|
| `client` | 客户 | AI 抽取 / 手填 |
| `matterType` | 事项类型 | **复用 `MatterClassifierService.java:29-33` 已有的法律事项分类 prompt**（公司治理/资本市场证券/并购交易/争议解决/合同审查起草/合规监管/知识产权/劳动人事/破产重整/其他法律事务/非法律事务）。它今天只上报埋点（`AgentOrchestrator.java:397-399` 异步触发、`:63 classifyAsync`），不写任何业务列——现在给它一个业务出口 |
| `openedAt` | 立项时间 | 默认取 `Project.createdAt`，AI 可推断更准的，律师可改 |
| `nextStep` | 下一步 | A 期由 AI 推断；**B 期接上任务系统后，只要有未完成任务就以最近那条为准，AI 推断退居第二**（律师自己排的永远优先） |
| `counterparty` | 对方 | AI 抽取 / 手填，可空 |

宁缺毋滥。新增字段是加行不是加列，零成本。

**不做「进行中/已完成」统计卡**：`userprofile.vue:81/:85` 现在是写死的字面量 `0`，`Project` 实体没有状态字段。搬到新列表页时**删掉这两张卡**，不要把假数字搬过去。

### 4.4 抽取链路

**不复用 `ProjectMemoryExtractor`**——纯正则（`LEGAL_REF:29 / AMOUNT:32 / DATE:38 / COMPANY:41 / PARTY:44`），产出不能给律师看：公司名抽了不落库、金额取全对话最大值且把模型自己生成的文本也算进去（`:53-63` 把 `AiMessage.text()` 一并拼进扫描内容）、日期键名是「日期1..5」且仅当现值为 null 才写（`:106-112`，写一次就冻结）。

新起 LLM 抽取链路写 `project_profile_field`：

- **触发：首次建项目时跑一次 + 用户点「重新分析」**，不挂 `onConversationTurnCompleted`。理由：挂每轮的话成本随对话长度线性增长且计到用户额度（`PlatformAiUserScope`），而 awaiting_approval/取消/报错路径又不触发，覆盖既贵又不全。
- 另设脏标记（挂既有 `signalChange` 调用点与「结束工作」），仅用于在概览页上提示「文件有变动，档案可能过期」，**不自动重算**。
- 一次 LLM 调用：喂全部文件名 + 少量文档开头，不喂全文。
- **必须有显式的「重新分析」按钮。** 平台 AI 通道按 Credits 计费，概览页不能在背后偷烧额度。
- 现有 `MemCellExtractor`（`MemoryPipelineService.java:108-113`，门槛 `MEMCELL_THRESHOLD=4`）是仓内唯一的 LLM 记忆抽取链路，比正则抽取器更接近可复用起点（**输出结构未验证**）。

`ProjectMemoryExtractor` 与 `project_memory` **保持现状不动**（它服务 AI 上下文），只是概览页不读它。

### 4.5 ECM 遗留字段：不动

`Project` 的 `projectType`（NOT NULL）/ `listedCompanyName`（NOT NULL）/ `targetCompanyName`（NOT NULL）/ 两个 `companyInfoJson` 是重大资产重组时代的遗留。三个创建路径都得兜底写空串（`ProjectService.java:76-77` 是唯一可能写非空值的，`CloudSyncService.java:257-258` 与 `LocalProjectService.java:99-100` 写 `""`）；前端全仓唯一 createProject 调用点 `newproject/index.vue:230` 只传 `{projectType:'BLANK', name}`。

**A 期不动这些列**，理由：

- `ddl-auto: update` **无法收回 NOT NULL**。去掉 `nullable=false` 后存量库的列仍是 NOT NULL，后端一旦停止写空串就 insert 500。
- 重命名会被 `BeanUtils.copyProperties`（`ProjectService.java:166-169`）静默吃掉：`ProjectCardDTO` 不编译报错，只会字段变 null，项目列表页悄悄空白。**要动就删干净（实体 + 两个 DTO + 三个前端展示点 + 三个后端写入点），不要改名。**
- 删列会连带砍掉 Tushare 变量自动生成入口（`ProjectService.java:102-112`，当前因前端只建 BLANK 项目而不可达）——需要先确认这个能力还要不要。

**权威裁定**：概览页的「事项类型」以 `project_profile_field.matterType` 为准；`Project.projectType` 降级为存量兼容字段，只在项目卡片上做旧数据回显（`config/projectTypes.js` 的 `getProjectTypeLabel` 保留，该文件其余四套 ECM 表单配置零导入方，属死代码）。冲突时不提示——两个来源不同源，提示只会让律师困惑。

`GET /api/projects/{id}` 目前返回**裸实体**，把两个 `companyInfoJson` 原样下发给包括 CLIENT 在内的全部成员。概览页新端点**一律返回 DTO**，不照抄。

---

## 5. 页面与路由

### 5.1 新增页面

`pages/project-list/project-list.vue` 与 `pages/project-home/project-home.vue`。

**`pages.json` 每页都显式写 `navigationStyle: custom`，globalStyle 里没有这一项**（`:81-86`）——**新页漏写这行会得到系统导航栏**。目录命名约定 `pages/<name>/<name>.vue`（唯一例外是 `newproject/index.vue`）。

**路由埋点不需要排工**：`App.vue:15-26` 的拦截器是全仓唯一 `addInterceptor`，只上报 path；后端白名单只约束属性键。顺手改的注释：`App.vue:14`、`sidebar-shell.md:47-53`。

### 5.2 搬迁清单（漏一条就出可见 bug）

**模板**：`:62-65`（header-actions，含「从团队案件库取一份案卷」`:63` 与「+ 新建项目」`:64`）+ `:72-214`（panel-projects）+ `:450-455`（InviteMemberDialog）+ `:458-461`（CloudAcceptDialog）。

**data 区间不纯**：项目相关是 `:506-510` + `:514-516`；**`:511 favoritesLoading` 与 `:512 favorites` 属收藏 tab，不能跟着搬**。

**方法**：`loadProjects:1058-1099`、`openInviteModal:1102-1105`、`closeInviteModal:1106-1109`、`removeMember:1110-1128`、`isProjectAdmin:1129-1134`、`getRoleLabel:1135-1144`、`getRoleClass:1145-1150`、`getInternalMembers:1151-1159`、`getClientMembers:1160-1164`、`getProjectTypeLabel:1165-1167`、`getProjectCardClass:1169-1178`、`shouldShowListedCompany:1179-1181`、`shouldShowTargetCompany:1182-1184`、`formatTime:1185-1196`、`goToProject:1212-1216`、`openCloudAccept:1217-1219`、`onCloudAccepted:1220-1223`、`handleDeleteProject:1224-1255`、`goToNewProject:1256-1260`、`startRename:1266-1269`、`confirmRename:1270-1289`、`cancelRename:1290-1293`。

**样式**：`:1517-1593`、`:1596-1627`、`:1629-1932`、`:2032-2066`、**`:2363-2379`（响应式，`:2372-2378` 专门重排 `.projects-stats-row`，漏了会丢窄窗表现）**、`:2591-2617`、`:2620-**2728**`。外壳 `.page-userprofile :1311-1516` 需复制一份。

- **`:2730-2735` 的 `.empty-icon` 服务收藏/待办空态，必须留在 userprofile。**
- **死样式不要搬**：`:2381-2451`（/* Members */）与 `:2453-2588`（/* Modal */）在模板里已无 class 命中（成员用的是 `-new` 变体、弹窗已组件化）。
- `.panel-projects:72`、`.loading-state:91`、`.loading-text:92` 三个 class 全文无样式定义，加载态本来就是裸文本——顺手补。

**必改的三处，漏了会静默出错**：

1. **`userprofile.vue` 的 `activeTab` 默认值改掉**（`:492` 现在是 `'projects'`），并删 tabs 数组里的 projects 项（`:494`）。不改就得到一个默认打开空白 tab 的个人中心。
2. **`CloudAcceptDialog` 与它的两个入口必须一起搬**（`:63` 有项目时的顶部按钮、`:102-105` 空项目态）。漏掉的话「从团队案件库取一份案卷」这个协作唯一入口整个消失。
3. **`login.vue:289-291` 对 CLIENT 角色强制只回个人中心**。项目 tab 搬走后 CLIENT 会落到一个没有项目的页面上——必须重判这个落点。

**顺手收敛**：`getRoleLabel:1136-1142` 自己硬编码了一份角色映射表，而 `sidebar-shell.md:43` 声明角色文案唯一来源是 `config/memberRoles.js`。搬迁是收敛这份重复的时机。

**`CollabDialog.vue:271` 的邀请话术**写死「1. 打开项目列表，点『从团队案件库取一份案卷』」，发给的是手上还没有案卷的人。搬完后这句仍要指向他真看得见的位置（`sidebar-shell.md:45` 立过这条红线）。

### 5.3 导航流

**「直达工作台」的出口共 5 条**，方案 C 每条都要重判：

| # | 位置 | 方案 C 下的目标 |
|---|---|---|
| 1 | `launch.vue:95-97`（桌面启动，读 `checkba_last_project_id` → 校验存在 → reLaunch） | 工作台（**不变**） |
| 2 | `login.vue:283-304` tryAutoResume（与 launch 几乎逐行相同的复制，浏览器访问团队服务器走它） | 工作台（不变） |
| 3 | `App.vue:44-45` 应用菜单「最近打开」 | 工作台（不变） |
| 4 | `utils/ideOpen.js:22` 打开本地文件夹/文件 | **工作台（不变）**。打开文件夹的预期是「立刻看到文件」，落概览会多一跳；带 `openFileId` 的分支（`:40-59`）更是必须直达 |
| 5 | `project-overview.vue:2677-2682` switchToProject（顶栏切换器） | **工作台（不变）**。切换器语义是「快速跳过去干活」，强插概览很烦 |

**通往「我的项目」的入口共 6 条**：

| 位置 | 新目标 |
|---|---|
| `launch.vue:99`（无最近项目兜底） | **项目列表页** |
| `project-overview.vue:38` → `goAllProjects:2683-2686`（工作台里明写「全部项目」） | **项目列表页** |
| `project-overview.vue:340` → `goToUserProfile:3693-3695`（rail 头像） | 个人中心（保持，目标页默认 tab 已变） |
| `newproject/index.vue:175-177` | **项目列表页** |
| `admin.vue:1672-1682` | 个人中心（保持） |
| `login.vue:290/:299/:392/:472`（浏览器登录落点） | **项目列表页**（`:289-291` 的 CLIENT 分支单独判，见 §5.2） |

**跳转方式**。页面栈多实例地雷（`sidebar-shell.md:65` 的活跃实例指针 `window.__checkbaActiveOverviewVm`）在三级跳下会更严重；且 `userprofile.vue:1213` 现在用 `navigateTo`，与工作台内切项目（`project-overview.vue:2681` reLaunch，注释写明「切项目不叠页面栈（多实例地雷）」）口径相反。写死：

> **凡是工作台参与的跳转一律 `reLaunch`；工作台之外的页面之间用 `navigateTo`。**

- 列表页 → 概览页：`navigateTo`
- 概览页 → 工作台：`reLaunch`
- 工作台 → 概览页 / 工作台 → 项目列表页：`reLaunch`
- 列表页 ↔ 个人中心：`navigateTo`

返回体验靠概览页内自绘的「进入工作台」/「返回项目列表」按钮，不依赖 `navigateBack`。概览页与列表页都要套活跃实例指针守卫。

**「最近项目」写入时机**：`recordProjectVisit` 只在工作台 `onLoad` 调。**决策：概览页也调 `recordProjectVisit`，只存 projectId，接受「启动直达永远进工作台、概览页永远不会成为启动落点」这条语义**（不扩 `recentProjects.js` 的存储格式）。

`admin.vue:1874` 切换本机工作区时 `removeStorageSync('checkba_last_project_id')` 后 reLaunch 回 launch。改 `launch.vue:99` 后这条链变成「切身份 → 落到项目列表页」，语义正确，**别把那行删了**。

**query 契约**：工作台现接受 `id` / `name` / `openFileId`。概览页接受 `id`，**并透传 `openFileId` 给工作台**。

**BrowserView**：非工作台页面**不必**自己 `setViewsVisible(false)`——admin / plugin-market / variable-library 三个非工作台页都没做也没问题，真正的兜底是工作台的 `onHide`/`onUnload`。抄无害，但别当前置条件排工。

### 5.4 e2e 必须同 PR 改

`frontend/tests/app-e2e/run.mjs` 把 userprofile 当作进工作台的必经之路硬编码：`:302`（解锁后允许落在 userprofile）、`:331`（直接 goto userprofile）、`:332`/`:346`（`waitForSelector('.project-item-card')`）、`:341`（点「我的项目」回 tab）、`:350`（点卡进项目）。J2「个人中心四 tab」与 J3「进入项目」两段旅程要重写为三级跳，`docs/QA_JOURNEYS.md` 同步。J9 的版本记录两步断言（`:479-489`）随 §3.5 第 7 条一起改。**不改就 `npm run test:app-e2e` 基线当场断。**

---

## 6. 概览页内容与数据源

形态：**一页纸卷轴**（单列纵向：档案头 → 统计条 → 动态 → 日程与任务 → AI 对话）。可打印/导出给客户。

### 6.1 数据源表

| 内容块 | 数据源 | 端点 | 需新增 |
|---|---|---|---|
| 档案头 | `project_profile_field` | `GET/PUT /api/projects/{id}/profile`、`POST /profile/analyze` | 全新 |
| 统计条 | `ProjectFileRepository` | `GET /api/projects/{id}/overview/stats` | 两个 count 查询（见 6.2） |
| 动态 | `GET /version/timeline?limit=5` | 复用，需修 | isInitialized 早退（见 6.3） |
| 后台 AI 任务 | `AgentRunRecord`（有 `projectId:35`，不按用户过滤） | 同上端点 | Repository 加 `findByProjectIdOrderByUpdatedAtDesc`，**无需改表** |
| 日程与任务 | B 期 | `GET /api/projects/{id}/tasks` | A 期返回空数组 |
| AI 对话 | `ProjectAiMessage` 会话聚合 | `GET /api/projects/{id}/conversations` | 见 6.4 |

### 6.2 统计条

**Repository 没有项目级的文件/文件夹计数**——现有四个 count 方法（`ProjectFileRepository.java:43/:48/:51/:78`）都不是。新增 `countByProjectIdAndIsFolderAndIsDeletedFalse`（两个查询，省一次全树传输），不要走 `?tree=true` 把整棵树（最多 3000 条）拉到概览页。

口径：

- **排除软删除/回收站**。`?tree=true` 端点的 SQL 已带 `isDeleted = false`；含已删行的 `findByProjectId`（`:10-13`）是给版本清单 capture 专用的，**不要拿它做统计**。
- **剔除系统目录**。`__staging_area__`（`StageQuotaService.java:40`）是普通 `ProjectFile` 行，会被任何 `isDeleted=false` 的计数数进去；`StageQuotaService.usage(folderId):158` 能算子树 count/bytes，可直接剔除。AI Assistant Files 同理。
- **文件夹统计只能按 parentId 树遍历**，不能按 filePath 分组——`filePath` 仅对文件有效（`ProjectFile.java:65-70`），文件夹没有物理路径。
- **`localRoot` 项目的措辞改成「已登记 N 项」**，不是「共 N 个文件」。3000 条上限 + 20 层深度 + 隐藏项跳过，而 `truncated` 只在 `OpenLocalResult` 返回值里、**不落库**，UI 无从知道自己在展示一个被截断的数字。外置盘拔出时文件数还会**冻结在最后一次成功对账的快照上且不会自己恢复**（`LocalProjectService.reconcileProject:150-154` 根不可达整体跳过）。

**不展示「项目大小」与「最近修改」**：编辑器保存路径**不更新** `ProjectFile.updatedAt` 与 `fileSize`（实测 `FileController.java:362-499` 段内 `setUpdatedAt|setFileSize` 零命中，只有 `signalChange`）。「最近修改」取版本时间线最新一条的 `when`。

**概览页不自己扫盘**：DB 文件树是唯一口径。实时扫盘会与 watcher 对账争用，且把 3000 上限与隐藏项规则搞成两套。

### 6.3 动态

**主源是版本时间线**（`VersionController.java:240-260`，message 已是律师语，无需加工）。

**必修（后端一行）**：未开启版本记录时 `/timeline` 返回 `{code:1,"版本记录操作失败，请重试"}`，概览页会把「还没开启」显示成「读取失败」。在端点加 `isInitialized` 早退返回空 `versions`。（或前端先读 `/version/status.enabled`——但那会触发 `git add`，见下。）

**最省的兜底源**：`WorkSessionRepository.findByProjectIdOrderByStartedAtDesc:12` 已声明、零调用、纯 DB、不开仓不取锁。局限：粒度到工作段、ACTIVE 行 title 恒 null。

**时间线文案共 6 种形状**，UI 要能容纳：① 律师起的工作名；② 未命名工作的 `defaultTitle`——`TITLE_FMT` 是 `"M 月 d 日"`（**带空格**，`WorkSessionService.java:37-38`），半天三档 上午/下午/晚上（`:1592`），拼出来是「8 月 8 日下午的工作」；③「采纳：{稿名}」（`:1172-1174`）；④「退回到早先的版本」（`:821-822`）；⑤「初始版本」（`ProjectRepoService.java:105`）；⑥ **「取回最新稿」**（云端整合的合并提交，`CloudSyncService.java:83/:689`）。

**`authorName` 不是可靠的「谁改的」信号，别拿它区分 AI 与人**：`"AI Workdeck"` 有两个语义相反的来源——`WorkSessionService.java:504-505` 是真 AI 轮次（`ai@`），`:398-399` 是同事 push 进来前把本机主线脏区停靠掉的那一笔（`system@`，**那其实是律师自己没存的编辑**）；而 `VersionEntry.java:14-23` 根本不带 email 字段。反过来，采纳/结束工作/云端整合的合并提交署的是**动手的人**，哪怕内容全是 AI 写的。

**A 期结论：动态条不标注 AI/人。** 要标注只能接 `ConversationFileChange`（`GET /api/ai/conversation/{id}/metadata`），而那张表**没有 projectId、没有 fileId**（只有 `fileName` 纯字符串，且同一会话内按 (fileName, changeType) 去重）——它不是时间序 feed，是「这次会话碰过哪些文件」的集合，项目级聚合要 N+1。收益不抵成本。

**`UserActivityLog` 不能用**（三条硬伤，要写进 spec 防止后来者捡起来）：① **没有 projectId 列**，`targetId` 一字三义（OPEN_FILE 时是 fileId、OPEN_URL 时恒为 0、WORK 时是项目 id），项目归属只能从 `metaInfo` 的自由文本 `"Project: {项目名}"` 正则抠——**是项目名不是 id，同名项目会错配**；② 唯一查询是 `findTop500ByUserIdOrderByTimestampDesc`；③ **默认关闭的手动开关**（`activityTracker.js:19` isRecording=false），且 `WORK` 类型当前永远不产生（`project-overview.vue:3412-3417` 调 `start()` 不传参 → `activityTracker.js:222-225` 的 `if (this.targetId)` 恒假）。顶栏那个「录制活动」按钮是**写侧开关不是读侧入口**。

### 6.4 AI 对话历史

**不用 `ConversationSummary`。** 这张表 schema 很全（含 `pendingTasks:89-90`）、Repository 已有项目级不按用户过滤的 `findByProjectIdOrderByUpdatedAtDesc:26`——**但它恒返回空**：`MemoryPipelineService.java:75-77` 用 `episodeResult.toEntity(conversationId, projectIdLong, null)` 造的带 projectId 的实体是**死变量、从未 save**；真正落库走 `MemoryManager.updateConversationSummary:571-598`，建实体时只 `.conversationId(...).build()`（`:585-587`），**不 set projectId/userId**。本机实测：conversation_summary 共 5 行，project_id / user_id 全为 NULL。加上门槛是单轮消息栈 ≥ 15（`MemoryPipelineService.java:31/:68`），**覆盖率约 6%（5/88）**。

修 `updateConversationSummary` 的 projectId 落库 + 回填历史行（可从 `project_ai_message` 按 conversationId 反查）列为**独立小任务**，不阻塞概览页。

**A 期方案**：直接复用 `GET /api/ai/conversations`（`AiChatController.java:122-136`）的输出——`ProjectAiMessageService.listConversations:166-189` **已在服务层做过清洗**（标题优先 storedTitle 否则 `cleanTitle:194-216`；预览 `extractPreview:222-264` 优先抽 `<final>`、否则剥 thinking/process/tool_code/tool_output/artifact/root_bubble 全部标签；`truncatePreview:269-286` 截 80 字并在标点处断），返回 `{conversationId, updatedAt, title, lastMessage, runStatus}`。

**别再抄第四套清洗正则**：服务端清洗一次，前端 `fetchChatHistory:4796` 又剥一次、`:4798` 再截 60 字——已经两套并行漂移，概览页必须直接用服务端输出。

**可见性口径（写死）**：`findConversationSummaries` 同时按 projectId 和 userId 过滤（`:25`）——是「我在这个项目里的会话」，不是「这个项目的全部会话」。改成全项目可见 = 把同事的对话正文（含文档原文、工具输出）暴露给所有成员，正是 2026-08 安全审计修过的那类问题。

> **决策：分层。列表层（标题/时间/发起人/状态）按项目全员可见，正文层仍按 `canUseConversation` 判权。** A 期概览页只做列表层。

**点击行为**：进工作台并打开这个会话（`conversationId` 经路由参数传给工作台，由工作台走既有 `loadHistoryChat` 链路）。**概览页不内嵌 `ChatInterface`**——`loadHistoryChat:4811-4847` 是完整切换会话不是只读回看，内部 `setConversationId` → `resetSSE` → `clearBubbles` → 逐条反解析 → `reattachSSE` 重连，在用户还没进工作台时就抢占当前会话。

**必修的鉴权与性能**：

- `/api/ai/conversations` 的 `X-Session-Id` 是 `required=false`（`:123`），无 session 时 `userId=null` 走进恒不匹配的 SQL 条件，**静默返回空数组而不是 401**（会被误诊成前端 bug）。新端点模板：`getUserIdFromSession` → null 返 401，再 `hasReadPermission` → false 返 403。返回信封而非裸数组。
- **`project_ai_message` 没有任何 `@Index`**，加上 `ddl-auto=update` 且无 schema.sql → 线上只有主键索引。按 projectId 铺全历史 + GROUP BY conversationId 是全表扫描。**加索引这一项必须显式排进实施清单。**
- **分页用游标不用 offset**：按 `(MAX(createdAt), conversationId)` 倒序，请求带 `before=<timestamp>`，默认 pageSize 20。会话列表按活跃时间排序且实时变动，offset 分页在刷新时会跳行/重复。注意 `findConversationSummaries` 的三个标量子查询用了 JPQL `LIMIT 1` + GROUP BY，**套 Pageable 需手写 countQuery 或改两段式**。

**两个已知的展示坑**（不修但 UI 要有兜底）：`extractPreview:259-261` 对以 import/def/function/class/const/let/var/public/private 开头的正文**直接返回空串**；`cleanTitle:196/:215` 对空白返回字面量「新对话」，与 LLM 生成失败写库的「新对话」同文案不同来源，前端无法区分。

**`runStatus` 是内存态**（`AgentRunStateService.java:49` 内存 Map，`:103-110` 读只看内存）→ **进程重启后所有历史会话的 runStatus 都变回 null**。概览页要显示运行状态就得改读 `agent_run_record` 表。

**管理缺口**：`controller/ai` 全目录**无 `@DeleteMapping`**——没有删除会话、也没有用户改标题的端点。概览页把「全部对话历史」铺开会立刻暴露这个缺口，spec 记为已知，A 期不做。

**成本聚合（可选）**：`TokenUsage` 有 `projectId:25`/`cost:46`/`costSource:57`。**红线：`costSource=platform`（真实扣费）与 `estimate`（单价表估算）必须分开标注、不得合并**（`.claude/agents/ai-chat.md:21`）。

### 6.5 轮询纪律

**概览页不调 `/version/status`。** 它在 enabled 时会跑两次 `git add`（`VersionController.java:68` → `WorkSessionService.java:340-348` → `ProjectRepoService.java:241-242`）。工作台已有 ≥7 处触发点（`project-overview.vue:2165` onLoad、`:2297` window focus、`:2785`/`:2791` collab 回调、`VersionPanel.vue:161` mounted、`:145-147` watcher、`:223-226` onReload），**一次 /status 已经在喂四处 UI**（`versionWorkStatus`，`:1703` → 顶栏 chip `:45-53`、底部状态栏 `:1375-1378`、待裁决固定条 `:1326-1327`、collab chip `:1860/:1864/:1868/:1871`）。概览页复用这份状态，别打第三次。

**A 期：只在进页面/切回时刷一次，不起轮询。** 若后续要加轮询，必须抄现有三道守卫（活跃实例指针、`document.hidden`、功能未启用不起，`project-overview.vue:2758-2766`），且不能与工作台的 15s `fetchChatHistory` 轮询（`:2329-2333`）叠加——建议把「会话列表 + 状态」抽成模块级单例 composable 两页共享（`useAgentStream` 已有此先例）。

### 6.6 空态

新建项目：档案全空、零对话、零动态。**不能是白板**。渲染引导态——一句「让 AI 读一遍项目里的文件，帮你把档案填上」+ 一个按钮，点了才花 Credits。这是这个页面的第一印象。

---

## 7. B 期骨架（任务与日程）

本节只定契约与选型结论，详细设计另出 spec。

### 7.1 许可硬约束

本仓 `LICENSE:1-2` 是 AGPLv3，`legal/CLA.md:13-14` 要求贡献者授予「以专有商业许可向第三方再许可」的权利，`legal/COMMERCIAL-LICENSE.md:4-8` 提供 copyleft 豁免。**CLA 只约束贡献者，对第三方项目的著作权人不成立**——任何 AGPL/GPL/EPL/MPL 代码抄进来，商业许可卖的就是维护者无权授出的东西。

1. 任务模块**自建**。开源项目只作阅读材料，**不复制代码、不复制注释、不做依赖**。唯一例外：Focalboard 的 `server/model/`（Apache-2.0）与 super-productivity（MIT）在许可上可抄。
2. 新增 npm/maven 依赖**只允许 MIT / Apache-2.0 / BSD**，PR 里记 SPDX。
3. CI 加依赖许可断言，**按 `package.json` 的 `license` 字段判，不按包名通配**。

### 7.2 选型结论

| 项目 | 许可（实测） | 可借鉴 | 可否用 |
|---|---|---|---|
| **Vikunja** | AGPL-3.0 | Task↔Project 1:N + Label/Assignee 关联表；CalDAV 作为对外日程出口 | ❌ |
| **Focalboard** | 三重：官方编译版 MIT / 源码 AGPLv3 或商业 / **`server/model/`、`webapp/html-templates/`、`app-config.json`、`config.json`、`webapp/i18n/`、`plugin/` 为 Apache-2.0** | **block 化模型（board/view/card/text 同表、fields 存 JSON），扩展新卡片类型零迁移——对 ddl-auto 环境有吸引力，且模型定义正好住在 Apache-2.0 目录里** | ⚠️ 仅 `server/model/` |
| **Planka** | **非开源**——PLANKA Community License v1.1（Fair Use，非 OSI），逐字禁止「为任何商业收益向第三方提供托管服务」 | — | ❌ 出局。（准确理由是「不能复制进对外销售的商业产品、不能作为托管服务对第三方提供」；其 Permitted Use (b) 是允许组织内部使用的） |
| **Huly / Tracker** | EPL-2.0（文件级弱 copyleft） | `identifier + sequence` 生成人类可读任务编号 `PROJ-123`（律师引用任务时比 UUID 好用） | ❌ |
| **Taiga** | taiga-back **MPL-2.0**（不是普遍传说的 AGPL）；next-gen 仓 2023-12 停更 | 无独有价值 | ❌ |
| **super-productivity** | **MIT**（仓库已改名为 `super-productivity/super-productivity`） | 硬经验：把「计划何时做」与「实际截止」拆成两组各自互斥的字段，为此写了整篇 ARCHITECTURE-DECISIONS | ⚠️ 可抄代码，但无成员/权限/多人概念 |

**五个后端候选没有一个可嵌入**：全部是「自带数据库 + 自带前端 + 独立进程」的完整应用。桌面端已打包 pptx/mineru/kokoro 三个 Python 服务，再加一个 Go/Node 常驻服务是净负担。

### 7.3 日历渲染：先自绘，不引库

`FullCalendar` 与 `schedule-x` 都把付费插件放在与免费包**同一个 npm scope** 下，一次 `npm i` 就可能装进商业授权组件；FullCalendar v7 起 copyleft 备选由 GPLv3 改为 AGPLv3。付费包实测名是 `@fullcalendar/timeline`/`resource`/`adaptive`/`scrollgrid` 与 `@schedule-x/resize`/`drag-and-drop`——**包名里既无 premium 也无 scheduler，按包名通配的 CI 规则一个都拦不住**。`vue-cal`（MIT、Vue3 原生、`dependencies=null`、无 premium 分层）是三者里唯一没有踩线面的。

**但更重要的是：本仓当前零个第三方 Vue 组件库**（`package.json` 只有 `@dcloudio/*` + vue + 工具型库）。引入 vue-cal 会是**第一个第三方 Vue SFC 组件库**，要过 `@dcloudio/vite-plugin-uni` 编译（模板用 `<view>` 不用 `<div>`）。

> **决策：B 期先自绘月视图**（月格 + 任务点），不引库。概览页的日程块只要这个程度，而自绘同时避开了「零先例引入」和「视觉红线」（全局禁 emoji、浅色外壳）两重风险。确有需要（周视图/拖拽改期）再引 vue-cal，届时照 `FilePreview.vue:139-145` 的 `#ifdef H5` 常量 + `:405/:433` 的动态 `import()` 范式，并先做最小 H5 冒烟。

### 7.4 任务实体最小字段集

**仓内对标比外部项目更有价值**：`DdItem.java` 就是「清单项 + 状态 + 指向 ProjectFile 的裸 Long 列」的完整实现（`:25-26 ddRequestId` / `:47-48 status String 带默认值` / `:53-54 sortOrder` / `:57 parentId` / `:63 level` / `:71-72 exampleFileId` / `:77-78 uploadedFileId`）；`ShareholderMeetingCheck.java:19-83` 同时示范了三种正需要的字段（`:41 LocalDate meetingDate` / `:51 Long noticeFileId` / `:69 String conversationId`）。全仓 39 个实体 grep `@ManyToOne|@OneToMany` **零命中**——裸外键是全仓一致做法。

```
project_task
  id              Long, IDENTITY
  projectId       Long, NOT NULL, 索引
  uid             String(36)    跨机器身份
  title           String(256), NOT NULL
  status          String        默认 "OPEN"。一律 String 不用 enum 常量列——改取值不改 schema
  dueDate         LocalDate     只做日期不做时刻
  relatedFileUid  String(36)    指向 ProjectFile.uid（ProjectFile.java:108）
  relatedFileId   Long          冗余，仅供本机快查
  conversationId  String(64)    关联到产生它的那次对话
  assigneeUserId  Long
  sortOrder       Integer
  createdBy       Long
  createdAt / updatedAt
```

- **`dueDate` 只用 `LocalDate`**：super-productivity 的教训——日期字段一旦允许「可选时刻」就必然分裂成四列并引入优先级读法。从源头回避，要「计划做 vs 截止」再加第二列（零成本）。
- **`relatedFileUid` 存 uid 不存数字 id**：`ProjectFile` 有两套身份，本机数字 id 跨机器无意义且会碰撞，清单 v2 里 uid 才是唯一可信身份。存数字 id 的话云端协作/接入他人案卷后会静默错配。
- **不要预先加的**：优先级、标签、子任务、重复规则、提醒时刻、看板列、附件、评论——全部等有人抱怨再加。

**实体样板必须先选边**：全仓 39 个实体里 **17 个用 Lombok `@Data`/`@Getter`**（含最新的 `ShareholderMeetingCheck:16`）、22 个手写 getter/setter。`@Data` 生成覆盖全字段的 equals/hashCode，是 JPA 经典反模式，**与 `DdItem:211-222`「equals 只比 id」的做法互斥**。

> **决策：手写 getter/setter + equals/hashCode 只比 id**，对齐 `DdItem`。理由：任务实体会被放进集合、会被 JPA 托管，全字段 equals 在懒加载与游离态下行为不可预期。

**边界划清**（否则 UI 上互相污染）：

- 与 `todo_write`（`TodoListService.java:38-39`，`ConcurrentHashMap<conversationId, List<TodoItem>>`，进程重启即失，三字段，强制「同一时刻最多一项 in_progress」）：那是 **AI 单次工作的步骤条**，UI 上叫「进度」；`project_task` 是**项目级里程碑**，UI 上叫「任务」。两个词不能混用。
- 与 `DdItem`：它已占了「交付清单」语义位。新任务实体不接管尽调清单，两者并行。

### 7.5 任务↔文档打通

**新建文档走 `ProjectFileService.createFile(...)`（`:210-269`），HTTP 入口 `POST /api/projects/{projectId}/files/file`（`ProjectFileController.java:196-218`，`:214` 强制把请求体 `filePath` 置 null 由服务端生成，安全审计遗留）。不要绕过它自己写库**（会丢 signalChange、排序号、同名校验、路径围栏）。`signalChange` 服务内部自带（`:265` 等七处），**任务侧不需要另调**。

**反过来，任务本身的增删改不进版本记录**（Git 只跟文件走）。

**两个风险**：

1. **模板缺失会造出 0 字节 docx**。物理文件是懒创建的（`createFile:262-268` 只调一次 `storageService.load`），`LocalFileStorageService.java:59-79` 发现文件不存在就从 `docs/template.docx` 拷一份，**模板不存在时只 log warn 然后 `Files.createFile` 落一个 0 字节空文件**（`:71-72`）——律师到交付日才发现 LOWA 打不开。**处理：新建后校验 `fileSize > 0`，或走 `write_docx`/`AiDocxExportService.exportMarkdownToDocx:41-62`**（会写入律所标准格式，带 `@ToolMeta(fileEffect="ADDED", refreshFiles=true)` 自动触发文件树刷新）。
2. **新建文档会经 `signalChange` 立刻隐式开一段工作段**（`onChangeSignal → ensureSession`）→「建任务顺手建空文档」会在版本时间线上凭空产生一段工作。B 期要么接受并在 UI 上说明，要么给这条路径一个「不触发工作段」的旁路。

### 7.6 接入点 checklist

| 事项 | 位置 |
|---|---|
| 分层 | Controller `@RestController` + `@RequestMapping("/api/tasks")` + 私有 `requireMemberByProject` / `requireMemberByTask` 链式助手（照 `DdController.java:31-46`、`ShareholderMeetingController.java:25-36`）；会话解析 `AuthController.getUserIdFromSession:640`，请求头 `X-Session-Id` |
| 权限 | **读走 `hasReadPermission`（含 CLIENT，与尽调清单同一套心智——交付日期恰是客户最关心的）；写走 `hasWritePermission && !isClient`。** 两套口径在仓里并存且抄错不报错，必须写死。**地雷：参数序是 `(projectId, userId)`，两个都是 Long，写反了能编译通过**。另注意 `isClient`（`ProjectMemberService.java:179`）是**三个字面量的显式 or**，不是 `startsWith("CLIENT")`（同文件 `:111` 用的却是 startsWith）——新增 `CLIENT_*` 角色时 `isClient` 会漏判 |
| SSE | **不新增 SSE 事件名。** `SseEmitterService.java:20-21` 的连接池 key 是 `conversationId`，用户在任务面板上改任务时没有活跃会话，新事件是一条永远不触发的通道。AI 侧改任务复用 `client_action`（`AgentOrchestrator.java:316-317` → `useAgentStream.js:1166` → `agentClientActions.js:20` 加 `refresh_tasks` 分支）；人手改任务前端本地更新 |
| 左栏面板 | 三处：`config/leftSidebarPlugins.js:3-73` 静态数组加一项（CLIENT 可见性在 `getPluginsForUser:79-84` 过滤）、`project-overview.vue:538` 的 sidebar-content 加 `v-else-if`、`leftPaneTitle:1880-1881` 特判。切换状态机已外置到 `panelSwitching.js:7-97` |
| 弹窗样式 | `awd-*` **没有集中定义**，在 `project-overview.vue`/`ChatInterface.vue`/`FileTree.vue` 各写一份 scoped 副本。新组件里的弹窗要么挂进 `project-overview.vue` 的根级弹窗层，要么复制一份，否则渲染成无样式裸框 |
| AI 工具 `task_*` | 四处，**不含编排器**：① `tools/TaskTools.java implements AgentToolComponent`（`ToolRegistry:122-131 @PostConstruct` 反射自动注册，最小样板 `TodoTools.java` 全文 41 行）；② `@ToolMeta` 填 displayName/category/refreshFiles；③ `utils/toolDisplayNames.js` 补一行；④ `system_prompt.md:417-433` 工具表补一行。`projectId/conversationId/userId` 由服务端强注入（`ToolRegistry.java:44 SERVER_CONTEXT_PARAMS`），**绝不让 LLM 传**。`task_` 前缀不受会话能力过滤（只有 `doc_/sheet_/slide_/office_` 会被过滤）；若要在某 Skill 激活时可用必须写进该 Skill 的 `allowed_tools`（base-tools 只有三个：`application.yml:266-267`） |
| AI 建任务要不要确认 | **走计划审批卡 gate。** 任务写库是真副作用，模型误建任务没有拦截就只能靠律师事后清理 |
| 埋点 | 事件名与字段过白名单，三处同步（`TelemetryAttrWhitelist.java:23-39` + 后端测试 + 官网仓 EVENT_WHITELIST）。漏掉不报错，数据静默丢失 |

---

## 8. 环境与部署约束

**没有数据库迁移体系。** 无 flyway/liquibase；`db/migration/init_user.sql` 是 MySQL 反引号语法、全仓零引用、无 `spring.sql.init` 配置，是死文件。四 profile 全 `ddl-auto: update`（`application.yml:24`、`application-desktop.yml:26`、`application-prod.yml:14`、`application-cloud.yml:30`）。

**三种数据库并存**：prod 是 **MySQL8**（`application-prod.yml:8-16`），default 与 cloud 是 PostgreSQL，桌面打包态是 H2 file(MODE=PostgreSQL)。**桌面壳开发态默认跑 prod(MySQL)、打包态才跑 desktop(H2)**（`desktop/main/services/backend-service.js:123`）——**本机改 schema 的验证环境和线上不是同一种库**，新表要在两种库上各验一次。

推论：新表零成本，但**字段只增不减不改类型**；NOT NULL 的收回、列重命名、类型变更都要手写 ALTER 并写进部署清单。

**多租户越权面**：`project_memory` 没有 userId/租户列，cloud profile 是多租户共库——概览页任何 HTTP 接口都必须经 `Project.userId` 或 `hasReadPermission` 做归属校验。这是新增接口最容易漏的地方。

---

## 9. 前置修复清单（不修就不能上）

1. **`isInitialized` 半残仓陷阱**（§3.3 闸门 3）——不修则默认建仓失败的项目永久不可恢复
2. ~~JGit `AddCommand` 三项行为实测~~ —— **已完成**（2026-08-08，`JGitAddBehaviorProbeTest`，结论见 §3.3 闸门 2）
3. **默认建仓触发点移出 `@Transactional`**（§3.3 闸门 4）
4. **`/timeline` 未开仓时的错误信封改成早退空列表**（§6.3）
5. **`project_ai_message` 加索引**（§6.4）——现在线上只有主键索引
6. **删项目清仓 + 清 AI 数据**（§3.5）——IDENTITY 主键复用会让新项目继承已删项目的档案
7. **`GET /api/projects/{id}` 改返回 DTO**（§4.5）——现在把两个 companyInfoJson 下发给 CLIENT
8. **`/api/ai/conversations` 无 session 时静默返回空数组 → 改 401**（§6.4）
9. **FSEvents watcher 过滤 `.awd/`**（§3.5）——一行改动
10. **工作状态点显示条件改成 `working && changedCount > 0`**（§3.4）——否则默认开启后恒亮
11. **项目列表 N+1**（每个项目一次 `getProjectMembers`，`Promise.all` 并发）——搬迁时让后端在 `ProjectCardDTO` 里带成员摘要，或先渲染再懒加载
12. **`login.vue:289-291` 的 CLIENT 落点**（§5.2）——项目 tab 搬走后 CLIENT 会落到没有项目的页面
13. **`admin.vue:1903-1907` 把 `/api/projects/my` 的裸数组当信封解**，list 恒空——顺手修一行，新页别照抄

**顺手的清理（可选）**：`variable-library` 页在 `pages.json:46` 注册但**无任何导航入口**（排除自身目录后 grep 零命中），确认为死页面，是否随本次删除。

---

## 10. 测试与验证

- `cd backend && mvn test`（JDK 21，**系统默认 25 会 SIGBUS**）
- `cd frontend && npm run check:emits`
- `cd frontend && npm run test:app-e2e`——**J2/J3 两段旅程要重写、J9 两步断言要改**（§5.4），基线会变
- **已落地**：`JGitAddBehaviorProbeTest`（含 node_modules + 用户自带 `.gitignore` + 点开头目录 + 嵌套 git 仓 + 指向外部的符号链接的真实目录，两个用例分别钉死 `add(".")` 的收录行为与 `info/exclude` 的生效）。**改动 `ProjectRepoService.init`/`commitAll` 的收录方式时必跑这个。**
- 待补：默认建仓在大 localRoot 目录上的耗时用例（缺真实样本，见 §11 Q1）
- 新表在 H2 与 MySQL 两种库上各验一次建表（§8）
- 领域文档更新（CLAUDE.md 维护规则）：`sidebar-shell.md`（两个新页面、导航流、术语表）、`version-control.md`（默认建仓、`.awd/profile.json`、`.gitignore` 新契约）、`ai-chat.md`（`project_memory` 表结构与契约——**全仓目前只有 `ai-chat.md:32` 一行提到 `ProjectMemoryExtractor`**）

**顺手修的文档腐烂**（这些注释不可信）：`version-control.md:27` 写 `describeChanges` 在 `:299`，实际 `WorkSessionService.java:1563`；`sidebar-shell.md:79` 写 `userprofile.vue(2158)`，实际 2736 行；`ProjectRepoService.java:170` 注释写「limit 上限 100」，代码里没有；`recentProjects.js:2` 注释自称「存 id 与访问时间」，代码无时间戳；`UserActivityLog.java:57-62` 注释写 duration 单位「秒」，实际毫秒。

---

## 11. 开放问题

**Q1 · 体量预检的字节阈值定多少？** 文件数沿用 3000 有依据（与导入上限同源），字节阈值没有。需要一个真实律师项目样本标定。本机样本只有托管项目（21 个共 93MB），localRoot 项目无样本。

**Q2 · 任务数据要不要进 Git 仓库？**（B 期）不进 = 同事从团队案件库取回案卷时任务不跟着走；进 `.awd/tasks.json` = 跟着走但要处理与档案同款的退回/采纳语义。**这个不对称（文件回滚、任务不回滚）要不要在 UI 上显式表达，也是本题的一部分。**

**Q3 · `task.conversationId` 的悬空引用**（B 期）会话可被回滚（`POST /api/agent/history/rollback`），且当前无删会话端点。接受悬空并提示「该对话已不存在」，还是级联清理？

**Q4 · 日历数据源只有 `task.dueDate` 吗？**（B 期）只有任务 = 几乎空白的月历；聚合 `ShareholderMeetingCheck.meetingDate`、DdRequest 截止日、版本记录 milestone 会丰富得多，代价是需要一个统一的「日程条目」只读投影模型（不新增表）。

**Q5 · 「设定日期交付某 doc」新建在哪个文件夹、用什么模板？** 当前只有一个全局模板 `docs/template.docx`。HR 模板包已有类似资产，是否按文书类型选模板？

**Q6 · B 期外部日历 provider**——飞书/企业微信/Exchange/CalDAV。牵动用户数据出境判断。

**Q7 · 新列表页的重命名交互**沿用「点名字=重命名、点卡片主体=进入」（`userprofile.vue:142`，已写进 `docs/QA_JOURNEYS.md`），还是改成显式「重命名」菜单？

---

## 12. 已证伪，不要写进 spec

调研过程中被对抗性核查推翻的结论，逐条记下来防止后续从旧笔记里捡回：

1. ❌「`launch.vue:95-97` 是唯一一处直达工作台」——至少还有 `login.vue:283-304`、`App.vue:44-45`、`project-overview.vue:2677-2682`、`ideOpen.js:22`
2. ❌「非工作台页面必须自己隐藏 BrowserView，否则出 bug」——三个非工作台页都没做也没问题
3. ❌「`project_memory` 的 projectName/projectType 等五列在自动链路下永远是 null」——有 `update_project_info` 写入通道，只是模型很少用（实测覆盖率 0）
4. ❌「`transactionAmount` 无法通过继续对话纠正」——可经 `update_project_info` 覆盖成任意值，且**那条旁路无金额上限钳制、不做 max 比较**，反而是第二个脏数据入口
5. ❌「prod / 云端是 PostgreSQL」——prod 是 MySQL8
6. ❌「`project_memory` 有 13 个字段」——15 个
7. ❌「`authorName === 'AI Workdeck'` 是区分 AI 改动与人工改动的唯一可用信号」——该字符串有两个语义相反的来源，且 `VersionEntry` 不带 email
8. ❌「概览页需要新建一张会话级汇总表」——`ConversationSummary` 已存在且已有项目级查询，问题在写入路径坏了
9. ❌「`ProjectAiMessageRepository` 现有方法一个都不能直接复用」——`listConversations` 可直接做 MVP
10. ❌「一次自动存档跑两次 `git add .`」——4 次
11. ❌「`kind=session` 的 `:614` 是 mergeCore」——是 `commitMergeResolution`；auto 侧还漏了 `:1392`（dockCurrentLine）
12. ❌「session 条目的 message 有 5 种形状、格式是『8月8日下午的工作』」——6 种形状，格式带空格「8 月 8 日下午的工作」
13. ❌「Planka 从第一条就不满足」——其 Permitted Use (b) 允许组织内部使用；出局理由要换成不能复制进对外销售的商业产品、不能作为托管服务对第三方提供
14. ❌「Focalboard 整仓 AGPL 不可用」——`server/model/`（数据模型定义所在）是 Apache-2.0
15. ❌「super-productivity 是唯一许可上可直接抄代码的」——Focalboard 的 `server/model/` 同样可抄
16. ❌「schedule-x 的事件弹窗是付费插件」——普通 Event Modal 是 MIT；付费的是 Interactive Event Modal
17. ❌「CI 加断言禁止 `@fullcalendar/premium*` / `*scheduler*` / `@schedule-x/*premium*`」——实测一个付费包都拦不住
18. ❌「本仓实体统一是手写 getter/setter + equals 只比 id + Service 用 `@Transactional`」——两种风格并存，最新样板用 Lombok `@Data`
19. ❌「`isClient` = 角色以 CLIENT 开头」——是三个字面量的显式 or
20. ❌「用 `UserActivityLog` 就能做项目级动态，不需要新表」——无 projectId 列、只能按 userId 查 500 条、默认不录制
21. ❌「`GET /api/ai/conversations` 可零后端改动直接接成『本项目全部对话历史』」——是 user-scoped，且有鉴权缺口
22. ❌「`project_memory` 是项目基本情况最合适的落点，不用新建表」——不进 Git 同步、无 uid

**标为「未验证」、不可当事实引用的**：~~JGit 6.9.0 对 `.gitignore`/嵌套 `.git`/符号链接的实际行为~~（**2026-08-08 已实测，见 §3.3 闸门 2**）；`enableVersionRecording` 在大项目上的耗时；默认开启后 `onChangeSignal` 早退翻转的性能影响；`MemCellExtractor` 的输出结构；`ProjectMemoryExtractor` 注释里自述的那次「生产事故」（无 issue/PR/日志佐证）；`findConversationSummaries` 在 userId=null 时返回空（SQL 语义推断，未实跑）；taiga-front 的许可。
