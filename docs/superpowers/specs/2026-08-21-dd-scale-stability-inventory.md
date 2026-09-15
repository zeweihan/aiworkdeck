# 尽调报告工况下的流畅度与大文档稳定性盘点（dev-board#100）

日期：2026-08-21。性质：只读盘点 + 一次真引擎实测，未改任何产品代码。
工况假设：报告 100-300 页 A4 docx（大量表格与图片）、项目内成百上千份关联底稿（pdf/jpg/png/音频）、几百张网核截图。
结论先行：维护者判断「架构够用、缺流畅度与大文档稳定性」成立。盘出的热点集中在四类——(1) office 单线程上的全文同步操作（全文枚举、修订、全文格式化、整档导出）；(2) 后端按「整份文件」处理的路径（Tika 全文抽取、检查点整份拷贝、Git 全量 add）；(3) 文件数量线性/平方放大的路径（新建文件的同级扫描、整棵树一次性拉取且无虚拟滚动、导入 3000 条硬顶）；(4) 几处上限口径互相矛盾（10MB / 2MB / 50MB / 30s / 180s）。

行号均以本树（`735c919a`）为准，改动后请重新 grep。

---

## 0. 实测数据（真引擎，150 页样本）

环境：借兄弟 worktree `elated-blackburn-ead7ab`（与本树同 HEAD 735c919a）的 `frontend/dist/zetaoffice` + r4 引擎（soffice.wasm 45.5MB br），无头 Chrome（puppeteer-core，`headless:'new'`），本机 Apple Silicon。样本由 python-docx 生成：150 页、921 段、30 张 12x5 表格、20 张 900x600 噪声 JPEG，**6.6MB**。命令走 `window.__loExecutor.executeCommand`（`?verify=1` 暴露的执行器，与生产 relay 同一条 worker 通道），耗时为 worker 内部 `performance.now()` 差（`inner_ms`），不含 CDP 序列化。

| 操作 | 实测 | 备注 |
|---|---|---|
| 引擎就绪（goto → 首条命令返回） | 约 3.5s | 热盘、无头；生产里冷启动受 150MB wasm 编译影响（固定端口缓存见 doc-editor.md） |
| `load_document` 6.6MB/150 页 | 2.7s / 3.8s（两次） | 期间 office 线程完全阻塞，无进度 |
| `get_ui_state` / `get_formatting` | 20-35ms / 8ms | O(1)，不是热点 |
| `get_document_text`（默认 200 段/15000 字预算，返回 132 段） | **2.2s / 5.2s**（两次） | 每次调用都从头枚举 921 段；AI 翻一页读 = 卡画布 2-5s |
| `get_document_text {startParagraph:800}` | 2.0s | 跳过前 800 段仍要枚举，证实 O(n) |
| `find_text_locations`，600 命中（顶 50） | **3.0s** | 50 个书签 + 上下文取回 |
| `find_text_locations`，1 命中 | 70ms | XSearchable 本身快，慢在每个命中的锚点/上下文 |
| `find_navigate`，600 命中（顶 500） | 2.6s | 每次导航重算全部命中位置 |
| `export_document` 6.6MB | **1.2s-2.3s** | 自动保存每次都付这笔；页面侧另有字节跨界拷贝 |
| `find_replace` 修订模式，150 命中 | **20.5s**（137ms/命中） | 36 命中 7.8s（216ms/命中）。后端 `EDITOR_ACTION_TIMEOUT=30s`：**约 200 命中即超时**，而 worker 仍在继续改 |
| `list_revisions`（300 条，顶 200） | 4.2s | 审阅面板一次刷新 |
| `resolve_all_revisions accept`，300 条 | **19.8s** | 「全部接受」按钮卡 20s |
| `apply_house_style`（921 段 + 30 表） | **>30s，执行器超时失败** | worker 未停（后续命令排队），宿主已报失败——「成功一半」的典型 |
| 页面总内存（`measureUserAgentSpecificMemory`，含 WASM 线性内存与 worker） | 约 2.4GB | 空白基线未测，不能把差值全归文档；保活 3 + 备胎 1 = 4 实例的 RSS 需用 Activity Monitor 复核 |

脚本（生成样本 + 驱动）在会话 scratchpad，配方见附录 A；可按需抄进 `frontend/tests/lowa-e2e/` 做成常驻「大文档基线组」。

---

## A. 大 docx 在 LOWA 里

### A1 打开耗时与内存

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 字节预取 | `frontend/src/components/LibreOfficeEditor.vue:735-755` `fetchArrayBuffer`，`xhr.timeout = 60000`（:744），失败重试一次（:624-632）；整文件 GET，无 Range/分片 | 50MB 文档弱网 60s 到顶即整份重下 | 下载超时按体积放大（如 60s + 每 MB 2s）；保留整文件 GET（本地后端无带宽问题） |
| 字节跨界 | `LibreOfficeEditor.vue:634,657` 把 `Uint8Array` 经 `executeCommand('load_document', {bytes})` 发出；三跳全是结构化克隆，没有 transfer list（`useZetaOfficeWebview.js:38/80`，`libreofficeExecutorClient.js:197`）；worker 端 `office_thread.js:2298` `Array.from(new Int8Array(...))` 把字节炸成装箱 JS 数组（zetajs 编组硬约束） | 50MB 文档峰值内存约为体积的 6-8 倍（三次克隆 + 每字节 8B 的 JS 数组） | webview 内两跳改 transfer（`postMessage(m, [buf])`）；`Array.from` 这步受 zetajs 约束无法省，但可在写 MEMFS 时改用 `FS.writeFile(u8)` 绕开（策略 1 本就是写文件再 `loadComponentFromURL`，:2320-2335，字节数组只在策略 2 `private:stream` 才必需） |
| 只读预览接力 | `LibreOfficeEditor.vue:564-606` 字节到手即 `docx-preview.renderAsync` 整篇渲染（`breakPages:true` 非懒加载） | 150-300 页含图的 HTML 全量渲染与引擎 boot 抢主线程，大文档上「感知更快」可能反成卡顿源 | 超过阈值（如 5MB 或段落数）只渲染前 N 页；或渲到 `requestIdleCallback` 里分片 |
| 引擎加载阻塞 | `office_thread.js:2274-2350` 同步 `loadComponentFromURL`，无进度；宿主里程碑只是滴答（`LibreOfficeEditor.vue:337-391` `startBootTrickle`），同一 `bootStageKey` 停 30s 亮「重试」 | 实测 6.6MB 2.7-3.8s；几十 MB 含高清扫描件的文档可能接近 30s 误亮重试 | stuck 阈值按字节数放大；加载阶段文案区分「引擎启动」与「文档解析」 |
| 保活池 | `frontend/src/pages/project-overview/librePool.js:9` `LIBRE_KEEPALIVE_MAX = 3`，注释「每实例数百 MB」；备胎常驻 1 个（:70-118）；淘汰前 `flushSave`（:145-159） | 实测单页约 2.4GB 分配量；4 个大文档实例并存有 GB 级内存风险，且常量不随文档体积调整 | 按已加载文档体积计权（大文档记 2-3 份额）；或 16GB 以下机器降到 2 |

### A2 自动保存

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 触发/延迟 | `LibreOfficeEditor.vue:779-784` `max(200, min(2500, 15000-脏龄))`；让路条件 :792-798（`_cmdBusy>0` 或距上次命令 <1.5s，且脏龄 <60s → 2s 后重试） | 60s 强制保存兜底意味着 AI 修订风暴期间最多每 60s 强插一次 1-2s 的导出冻结 | 强制阈值按上次导出耗时放大（导出 2s 的文档 60s 放到 120s） |
| 导出本体 | `office_thread.js:2369-2432` `storeToURL('private:stream')` 全文同步序列化，经 `XOutputStream.writeBytes` 攒成 JS 数组再拼 `Uint8Array`（:2385-2389, 2429-2432） | 实测 6.6MB 1.2-2.3s，期间画布冻结（注释 `LibreOfficeEditor.vue:787-791` 已写明）；几十 MB 含图文档可到 5-10s | 无增量导出可做；只能「少导」：每 N 次 modified 与空闲时机合并、AI 工具在飞时不导（已有）、关闭/切换时再强制 |
| modified 循环 | worker 端 `exportInFlight` 闸 + 导出后恢复 `isModified`（:2400-2406，监听器 :1449）；宿主端 `export_document` 不计入活跃编辑（`LibreOfficeEditor.vue:481`）；节流 `editor-main.js:179` 前沿丢弃 500ms | 已修（注释记录「每 3 秒一轮整份上传」事故）；大文档若循环复发代价翻倍 | 保留 e2e 探针（autosave-export-modified-loop 记忆）；大文档基线组里加「连续 30s 无 modified」断言 |
| 上传 | `LibreOfficeEditor.vue:888-899` 整文件 multipart，无分片；失败 15s 慢速重试（:806-809） | 本地后端无带宽问题；Web 态（iframe 传输）几十 MB 每 2.5s 一次会打爆带宽 | Web 态自动保存间隔至少按体积放大 |
| 版本记录联动 | 每次上传 `signalChange` → `WorkSessionService.onChangeSignal`（`WorkSessionService.java:216-240`），防抖 2 分钟（:55），真正提交见 A6 | 自动保存不直接等于提交，防抖已合并 | 无需改 |

### A3 修订（redline）

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 批量替换 | `office_thread.js:1632-1645` `find_replace`：每命中 `selectVisibly` 滚视图 + `applyMinimalRedline`；`minimalEdits` 有界 LCS，`oMid.length*nMid.length > 250000` 退化整段替换（:331-337），单处 O(m·n) 有上界 | 实测 **137-216ms/命中**（大头在 UNO 往返与滚视图，不在 LCS）；150 命中 20s；**约 200 命中撞 `EditorBridgeService.java:66` 30s 超时**，worker 还在继续改、后端已告诉模型失败，模型可能重发一次造成双改 | (1) 去掉每命中 `selectVisibly`，只在结束时滚到首处；(2) 超过 N 命中时 worker 分批并回传进度（SSE 有现成通道）；(3) 后端把 find_replace 一类批量工具的超时提到 120s，或改「先数命中再决定是否拆批」 |
| 审阅面板 | `list_revisions` 顶 200/500（:3418-3424）实测 4.2s；`resolve_all_revisions`（:3492-3499）一次 dispatch 但前后各 `countRedlines()` 全枚举（:203-210），实测 300 条 19.8s；逐组处置 `ReviewPanel.vue:148-162` 串行 `resolve_revision`，每条 `redlineAt(index)` O(index) + 两次 `countRedlines()` O(N)（:3477-3491） | 上千条修订时按组处置近 O(K·N) 次枚举；「全部接受」卡 20s 且无进度 | `resolve_revision` 批量版（一次命令处置一组、只数一次）；面板刷新带 `since` 游标；大文档上把「全部接受」做成带遮罩的长任务 |
| 页边模式 | `ShowChangesInMargin`（:1389-1391）依赖 r3+ 补丁；非纯视图设置 | 大量修订时页边互叠（已知残留）；对性能影响未测 | 不动 |

### A4 worker 命令复杂度（每次 AI 工具调用都在 office 单线程上同步跑）

并发模型：`libreofficeExecutorClient.js:151/154` 与 `zetaOfficeRelay.js:72/75` 只有按 reqId 的 pending Map，**没有队列/互斥**；串行化完全来自 worker 单事件循环——一条 20s 的命令把后面所有命令（含自动保存的 export、IME 快捷键的 `ui_command`）全部排队。超时：默认 30s，`load_document`/`export_document` 180s（`libreofficeExecutorClient.js:189-191`，`zetaOfficeRelay.js:108-112`）。

| 命令 | 实现 | 复杂度 | 备注 |
|---|---|---|---|
| `get_document_text` :2451-2477 | `eachParagraph`（:169-178）从头枚举到末尾算 `total` | O(全文段落) 每次 | 实测 2-5s；AI 逐页读 300 页 = 每页付一次全扫 |
| `get_paragraph`/`modify_paragraph` :1744-1775 | 手写枚举到第 N 段 | O(N) | 同上 |
| `get_outline` :1777-1790 | 全枚举**无上限** | O(n) | 唯一连封顶都没有的 |
| `get_clauses` :1796-1841 | 全枚举，输出顶 300 条（:1818），无 truncated 字段 | O(n) | 超 300 条静默截断 |
| `insert_under_heading` :3010-3031 | 线性扫到命中标题 | O(n) | |
| `apply_house_style` :2946-2984 | 顶层元素顶 5000（:2949）；每表 `styleTableStandard`（:712-732）逐格设属性 | O(段 + 表×格) | **实测 >30s 超时**；超 5000 元素静默不处理且返回 `success:true` 无 truncated（:2981） |
| `find_text_locations` :1665-1691 | XSearchable + 每命中插书签 + 6 次光标遍历，顶 50 | O(命中) | 实测 3.0s@50；书签跟文档一起存进 docx |
| `find_navigate` :1932-1968 | 收集 ≤500 命中后 `compareRegionStarts` 定位当前序号 | O(命中) 每次导航 | 2.6s |
| `set_selection`/`replace_at_position` :1853-1872 | `getBookmarks().getByName` | O(1) | 不是热点 |
| `list_comments` :3500+ | 文本字段枚举，顶 500 | O(字段数) | 批注多的尽调稿尚可 |
| `insert_image` :3218-3250 | 逐字节 `charCodeAt` + `Array.from(Int8Array)`，**无字节上限**（只封显示宽度 `MAX_W=15000`） | O(图片字节) | 上限只在 Java 侧 2MB |
| `get_ui_state`/`get_formatting` :2000/:2889 | 读光标属性 | O(1) | 轮询安全 |

建议（A4 总）：(1) worker 内建一张「段落游标缓存」——`get_document_text` 记住上次 `(startParagraph → enumeration 位置)` 或一次性建 `index → XTextRange` 数组并在 modified 时失效，把逐页读从 O(n) 降到 O(窗口)；(2) `total` 另走一次性计数并缓存；(3) 凡有上限的原语统一返回 `truncated`；(4) 大命令（`apply_house_style`、`find_replace` >50 命中、`resolve_all_revisions`）拆批并发进度，宿主显示「AI 正在处理第 x/y 处」。

### A5 AI 改文档链路：读取上限与上下文

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 桥超时 | `EditorBridgeService.java:66` 30s，`:286` `future.get`；`doc_start_stream` 走 `doc_open_file_sync`（`DocumentEditTools.java:209-214`）也吃这 30s，而前端 load 预算是 180s | 整档加载类动作两端预算不对称 | 按 action 分级超时（open/stream 180s、批量改 120s、其余 30s） |
| 读全文 | `doc_get_document_text`（`DocumentEditTools.java:570-586`）透传分页，worker 15000 字/次、≤500 段 | 300 页约 40-60 万字 = 30-40 次翻页，每次 2-5s 全扫（A4） | 同 A4(1)；另给模型 `get_outline` + 按标题区间读的组合 |
| 活动文档进上下文 | `ContextAssemblerService.java:723-746`：内联正文（≤200000 字，:711）> 哈希缓存 > **回退 `legalTools.read_document`（:745）** → `DocumentTextService.java:67` Tika `setMaxStringLength(5MB 字符)` 同步全文解析；再截 `max-chars-per-file: 50000`（`application.yml:262`） | 缓存未命中时**每轮对话**都可能对几十 MB docx 做一次同步 Tika 全文解析，解析完才截断 | 回退前看文件体积与段数，超阈值改走编辑器桥的 `get_outline` + 前 N 段；或把 Tika 结果按 fileId+mtime 落盘缓存 |
| 工具读文件上限不一致 | `FileTools.java:163` `read_file` >10MB 直接拒；`extract_file_text`（:258-297）/`read_document` 无体积闸；输出统一截 `ToolFileGuard.MAX_TOOL_TEXT_CHARS = 80_000`（:47）；文件夹材料闸 10MB（`FileContextLoader.java:202`，`application.yml:260`） | 同一份 30MB 报告，三个入口三种结果，模型会反复换工具试 | 统一一个「大文件策略」：超阈值一律可读但走分页/摘要，错误文案指向正确工具 |
| 分片上传 | `FileTree.vue:3422` `CHUNK_SIZE = 5MB` 串行分片，每片 60s、重试 3 次；后端 `FileController.java` ~:400-460 `resolveUploadStoragePath` 已修「第 2+ 块写进孤儿文件」地雷；无校验和 | 已修；剩余风险是断网续传后无内容校验 | 上传完成回传 sha256 比对（可选） |

### A6 检查点 / 版本记录对大文件

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 检查点 | `DocumentCheckpointService.java:54-73` `getFileBytes` 整份进堆 + `storage.save` 整份落盘；每轮每文件一次；`clearForNewRun`（:129-143）清理 | 50MB 文档一次 50MB 堆分配 + 50MB 写；多会话并发叠加无限流 | 改流式拷贝（`Files.copy`）；云端 1.5GB 堆下给并发检查点加信号量 |
| Git 提交 | `ProjectRepoService.java:178-179` 两遍 `add(".")`，无 gitignore/体积过滤（注释 :63-73 自认）；`blobAt` 50MB 闸（:74）；`RepoMaintenanceJob.java:29-40` 每日 gc 不清历史 | docx 是 zip，Git delta 几乎无效，仓库随每次停顿提交近线性膨胀；超 50MB 的文档在版本记录里不能看/比 | 对 >N MB 二进制（音视频、扫描件）默认不入库（`.awd/` 里写 ignore 清单）；docx 仍入库但给项目设置里一个「版本保留天数」 |
| 对比 | 桌面 docx 走修订稿对比 = 两版都要 `load_document` + diff | 大文档对比等于两次打开 + 一次全文 diff | 先比段落哈希只对改动段落 diff |

---

## B. 成百上千文件的项目

### B1 文件树与索引（后端）

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| **导入硬顶** | `LocalProjectService.java:40` `MAX_IMPORT_ENTRIES = 3000`，`:351/:397` 超出 `truncated=true` 只 `log.warn`（:197），**对账从不回头读被截断的部分**（:195 注释） | **千份底稿 + 几百截图 + 目录项很可能突破 3000**，超出的文件在工作台里永远不可见、AI 也搜不到，且用户无任何提示 | 把上限提到 2-3 万并在界面上显式提示截断；或改为分批导入 |
| 对账 | `reconcileProject`：两次全表 `findByProjectId`（:201, :335）+ `walkFileTree`（:345，深 20） | 每次对账 O(N)，不是按变更子集 | 先用 watcher 事件定位变更子树，只对账子树 |
| watcher | `LocalRootWatchService.java:43` `DEBOUNCE_MILLIS = 800`，每事件重排（:165-179） | 截图逐张保存（间隔 >800ms）→ 每张触发一次全量对账 | 防抖 800ms 之外再加「最短间隔 5s」的节流 |
| **新建文件 O(N²)** | `ProjectFileService.java:224-229`（`createFile`）与 :91-96（`createFolder`）：每次新建先同名查询，再 `findByProjectIdAndParentIdOrderBySortOrderAsc` 拉整个同级列表只为算 `maxSortOrder` | 300 张截图进同一文件夹 ≈ 45,000 行累计读取，同步在每次 HTTP 请求里 | 改 `select max(sortOrder)` 单条 SQL；批量导入时在内存里递增 |
| tree.json | `ProjectTreeManifestService.java:59` `capture` 全表 + 排序，`apply` 内核再查两次（:183, :330-334）；8 个调用点；每次整份重写 | 1000 文件的 tree.json 约几百 KB，每 2 分钟自动存档重写一次，可接受；复合成本在与 Git 全量 add 同一次提交 | 暂不动 |
| Git 全量 add | `ProjectRepoService.java:178-179` 两遍遍历工作区全部文件（含 pdf/jpg/音频） | 每次提交 O(全部文件) stat；千文件级尚可，万文件级开始秒级 | 同 A6 的 ignore 清单；第二遍 `setUpdate(true)` 可合并 |
| AI `list_files` | `FileTools.java:196-241` **无分页无上限**，且每次调用 `dbPathIndex`（:712-728）全表 + 逐行回溯父链拼路径，无缓存 | 几百张截图的文件夹一次 list 把几百行塞进模型上下文；每次工具调用重建全项目路径索引 | 加 `limit/offset`（默认 100）+ 「还有 N 个」；`dbPathIndex` 按 projectId 缓存、文件变更时失效 |
| `search_project_files` | :73-153 文件名 glob，命中 50 终止（:105）；不读内容 | 安全 | 缺内容搜索是功能缺口不是性能问题 |
| `extract_file_text` 文件夹 | `describeFolder` 顶 200（:307） | 安全 | |
| 向量索引 | `ProjectRagService.java:79-135` `buildRetriever` 同步全目录 Tika + 逐段串行 embed，`scanDirectory`（:137-167）无上限；唯一消费方 `DynamicContentRetriever` 已与 v1 链路一起摘除（类注释 :14-27），`refreshProjectKnowledgeIncremental`（:69-76）只清缓存 | **当前不在热路径**；一旦有人重新接 `getRetrieverForProject`，千文件项目会同步卡数分钟 | 标注为「禁止直接复用」或删掉；真要做内容检索另起异步索引 |
| 列表接口 | `ProjectFileController.java:67-93` `?tree=true` 整棵树一次返回，标签走 IN 查询非 N+1（`FileTagService.java:64-74`） | 1000 节点一次下发约数百 KB JSON，可接受；万级开始明显 | 暂不动，配合前端懒展开 |

### B2 前端文件树

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 一次性拉树 | `FileTree.vue:1292` `getProjectFiles(projectId, null, true)` | 见 B1 | 首屏只拉根层，展开时按 parentId 拉 |
| 无虚拟滚动 | `:422`/`:561` `v-for="(item, index) in displayFiles"` 整段渲染；`recycle` 关键字只是回收站 | 展开含几百张截图的文件夹 = 几百个节点一次挂载，H5/uni 渲染层卡 | 引入窗口化渲染（uni 可用 `recycle-view`/手写 IntersectionObserver 分页）；或折叠超 100 子项的文件夹显示「展开更多」 |
| 树构建 | `:1746-1809` `buildTreeView` 每层对全量 `allFiles` `filter+sort` 递归；`refreshTreeView`（:1810-1830）整棵重算 | O(N × 展开文件夹数)，排序/过滤切换时整棵重算 | 先按 parentId 分组一次（Map），递归只取子集 |
| 缩略图 | 无服务端缩略图（grep `thumb` 只命中 CSS）；列表用类型图标不拉原图；单图预览 `FilePreview.vue:519/598` 原图整包下载 `createObjectURL` | 列表安全；预览几 MB 截图每张整包 | 后端加 `?thumb=320` 按需生成缓存（`Thumbnails`/ImageIO） |

### B3 图片进文档

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| `doc_insert_image` | `DocumentEditTools.java:1301-1337` 上限 2MB（:1326），`readAllBytes` → base64 → 经 SSE `client_action` 单帧下发 → worker 逐字节解码（`office_thread.js:3218-3250`） | 网核截图常 1-5MB，超 2MB 直接拒绝；base64 膨胀 33% 走 SSE | 后端先压缩/缩放到 ≤1600px JPEG 再下发（Java ImageIO 几十毫秒），上限提到 10MB 原图 |
| `write_docx` | `FileTools.java:393-451` flexmark `DocxRenderer`，**无图片资源绑定代码** | markdown 里的图片引用落不进 docx | 生成后用 docx4j/POI 二次注入，或明确在工具描述里说「不含图」让模型改走 `doc_insert_image` |

---

## C. 外部截图批量落盘

| 项 | 现状 | 预估瓶颈 | 建议 |
|---|---|---|---|
| 上传上限 | `application.yml:33-36` multipart 20GB；`ai.files.max-file-size-bytes` 10MB（:260）是上下文注入用，别混淆 | 无实际限制 | 无需改 |
| 批量/并发 | `FileController.java:362-365` 单文件 upload 且需先有 fileId；`ProjectFileController.java:217-238` 单条建元数据；无批量端点、无限流、无 chunk（grep 无命中） | 几百张 = 几百对「建记录 + 传字节」请求；服务端只靠 DB/JGit 锁背压 | 加 `POST /files/batch`（multipart 多文件、一次事务、内存递增 sortOrder） |
| 同名冲突 | `ProjectFileService.java:224-226` 新建同名直接抛「已存在同名文件」；前端自动加 `(1)` 只在「新建 Word」一处（`FileTree.vue:1408-1417`），截图保存链 `ocrActions.js` 未复用 | 手机/浏览器默认文件名相同的截图批量保存会连续失败 | 服务端统一「同名自动 (n)」策略，或 `createOrUpdateFile` 语义给调用方选择 |
| 截图链路 | `desktop/main/main.js:1175-1182` `capturePage().toDataURL()` → IPC base64 → `ocrActions.js:342-343` `fetch(dataUrl).blob()` → `:358-365` createFile → `:379-393` `uni.uploadFile`；四跳两次 base64，无「保存全部」 | 单张可用；几百张要人工重复几百次，且每次命中 B1 的 O(N) 同级扫描 | 主进程直接落盘到临时文件后传路径；加多选/连拍「批量保存到文件夹」 |
| 手机中转 | `MobileRelayController.java:80-101` `/media` 单文件 `MultipartFile`，ACK `:157-161`，状态批查 `:110-121`；`MobileRelayStoreService.java:53` `MAX_DIR_ENTRIES = 1000` 只限目录条目 | 目录镜像 1000 条硬顶——千份底稿的项目在手机端目录会被截 | 目录镜像分页或提上限；上传侧并发靠客户端队列（已有断点恢复） |

---

## 按风险排序的前 10 条待办（上百页报告 + 千份底稿）

1. **`MAX_IMPORT_ENTRIES = 3000` 静默截断**（`LocalProjectService.java:40/351/397`）——IDE 化项目底稿超 3000 项就永远丢文件且无提示。数据可见性问题，优先级最高。
2. **批量改稿撞 30s 桥超时而 worker 仍在改**（`office_thread.js:1632-1645` + `EditorBridgeService.java:66`）——实测 137-216ms/命中，约 200 命中即超时；模型收到失败后重发会双改。分批 + 进度 + 分级超时。
3. **`apply_house_style` 150 页即超时且 5000 元素静默截断**（`office_thread.js:2949/2981`）——「成功一半」无任何标记。拆批 + `truncated`。
4. **`get_document_text` 每页 O(n) 全扫 2-5s 冻画布**（`office_thread.js:169-178, 2451-2477`）——AI 读 300 页要扫 30-40 遍全文。段落游标缓存。
5. **活动文档回退 Tika 全文解析每轮可能重跑**（`ContextAssemblerService.java:745` → `DocumentTextService.java:67`）——几十 MB docx 同步解析完才截 50000 字。按体积改走桥读大纲/落盘缓存。
6. **新建文件 O(N²) 同级扫描 + 同名即失败**（`ProjectFileService.java:224-229`）——几百张截图落盘的直接痛点。`max(sortOrder)` 单查 + 同名自动编号 + 批量端点。
7. **审阅面板批量处置 20s 无进度、逐组 O(K·N)**（`office_thread.js:3477-3499`，`ReviewPanel.vue:148-162`）——上千条修订的尽调稿审阅体验。批量 resolve 原语 + 长任务遮罩。
8. **前端文件树整棵拉取 + 无虚拟滚动 + 每层全量 filter**（`FileTree.vue:1292/422/561/1746-1809`）——千节点树的卡顿来源。按需展开 + 窗口化。
9. **保活 3 + 备胎 1 的内存不按文档体积计权**（`librePool.js:9`）——实测单页约 2.4GB 分配量；4 个大报告并存有 OOM 风险。计权 LRU，先用 Activity Monitor 复核 RSS。
10. **Git 全量 add 无体积过滤 + docx 无 delta + 50MB 读取闸**（`ProjectRepoService.java:74/178-179`，`RepoMaintenanceJob.java:29-40`）——含图报告反复编辑让 `.git` 线性膨胀，超 50MB 后版本记录里看不了。ignore 清单 + 保留期。

次级（不进前 10 但值得记）：`doc_insert_image` 2MB 上限对截图偏紧（B3）；`list_files` 无分页 + `dbPathIndex` 无缓存（B1）；`find_text_locations` 书签随文档存盘（A4）；`read_file`/`extract_file_text`/材料闸三种大小口径（A5）；`ProjectRagService.buildRetriever` 是休眠的同步全项目重扫陷阱（B1）；只读预览全量渲染与 boot 抢 CPU（A1）；`LocalRootWatchService` 逐张保存触发逐次全量对账（B1）。

---

## 附录 A：实测配方（可抄进 lowa-e2e 做「大文档基线组」）

样本生成（python-docx + PIL）：150 页，每页 1 个二级标题 + 4 段约 220 字中文 + 分页符；随机 30 页插 12x5 `Table Grid` 表，随机 20 页插 900x600 `os.urandom` 噪声 JPEG（quality 75，约 330KB/张），结果 921 段 / 6.6MB。

驱动：仿 `frontend/tests/lowa-e2e/run.mjs` 起 COOP/COEP 静态服务（dist + `/lowa/` 引擎 + `/big.docx`），puppeteer-core 无头 Chrome 打开 `editor.html?verify=1&lowa=/lowa/`，`waitForFunction('!!window.__loExecutor')`，页内 `fetch('/big.docx')` → `Array.from(new Uint8Array(buf))` → `executeCommand('load_document', {bytes, name:'big.docx'})`，随后逐条计时（`performance.now()` 包在 `page.evaluate` 内部，`bytes`/`matches`/`paragraphs` 在页内裁掉再跨界，避免 CDP JSON 序列化把耗时算进去）。内存用 `performance.measureUserAgentSpecificMemory()`（需跨源隔离，刚好具备）。

两次运行的原始数字见上表；差异（`get_document_text` 2.2s vs 5.2s）说明同机抖动可达 2 倍，做基线组时取 3 次中位数、阈值放 3 倍。
