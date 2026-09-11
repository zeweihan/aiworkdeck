# 编辑器修订、批注与输入回归：暂停交接

2026-09-11，用户因 Codex 额度不足，明确要求暂停、按现状提交，交由 Claude 继续。**这是 WIP 快照，不可合并发布；没有完成验收。** 不要把“已编译”“旧引擎测试通过”当成新引擎或用户体验通过。

## 从哪里继续

- 仓库：`zeweihan/aiworkdeck`，分支 `codex/native-review-587`。
- 工作目录：`/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.worktrees/native-review-587`。
- 基线：`dd88a2a86202a8bd95ed981d787d89014df261ef`。前次 PR #812 已合并，但用户实测不满意，本次是在其后重做。
- **不要在主工作目录开发或重置它**：主目录分支 `codex/local-desensitize-restore` 有用户无关的未提交改动。
- 开发看板：`zeweihan/dev-board` #586（拒绝删除）、#587（原生审阅布局及性能）、#600（中文确认后不显示）、#601（右键松开菜单消失）均保持进行中；#588 是此前表格修订分组，保持待复测并做回归。
- 本次未改 CI 引擎下载地址，未发布 r5，未安装或替换用户正在用的 0.40.0。

## 用户要求与现场保护

用户要 Word 近似的统一原生页面：批注在纸张外，修订选择气球显示时删除内容在气球内；正文与气球一起滚动、准确定位、有连接线；不再出现独立空白“批注与修订”栏及另一个挤占正文的列表。长删除可完整查看，表格一次插入不拆为逐单元格事件，拒绝删除一次就应恢复。性能和日常输入必须优先。

0.40.0 又出现两个严重回归：搜狗中文候选词**已经选中**，文字仍要点击空白才显示；选中文字右键，松开右键菜单消失，只有按住右键移到菜单上才保留。用户认为第一次实时智能审查更新还行，后续审查/批注更新后出问题，若修不好考虑回退；尚未要求实际执行回退。

**用户原应用有未保存内容／保存失败状态，禁止重启、退出、覆盖安装或刷新原窗口。** 实验只用独立浏览器/Electron 进程和测试副本。只读保存副本位于 `/tmp/native-review-private-587/user-review-saved.docx`（私密、本地使用，禁止提交/上传）。保存副本有 25 条 native 修订、3 条批注、0 条删除，不包含用户尚未保存的现场删除。

## 已找到的根因

1. 旧布局用 `createTextRangeByPixelPosition` 反复二分反推每个锚点，每次滚动/缩放再做，阻塞同一引擎线程。12 个锚点 216 次查询约 2662ms，实际副本首次 465 次约 2565ms，滚动后累计 1079 次约 5402ms。已删除这条扫描路径，改原生布局直接返回坐标。
2. 同引擎、同保存副本：旧审阅刷新开启时中文提交约 2546ms，300ms 后画布仍未更新；关闭这段刷新后约 59ms，300ms 内出现文字。这是刷新阻塞证据，不能据此声称新引擎已实测修好。
3. 删除可以叠在插入层上。一次拒绝顶层删除后正文恢复，但修订数量和 Identifier 可能不变。旧代码只判断数量，误报失败；第二次可能操作底层插入。另外 ShowAnnotations 延迟触发的 XModify 会让全局 generation 校验误拒第一次点击；卡片在 pointerdown/click 之间重建也可能吞操作。
4. 新建 native 批注的 Name 可能为空，多个批注不能以空 Name 当身份。已验证 ParaId 是十六进制 native PostItId，可作为唯一 fallback。
5. 先过滤 Insert 再分组，会把 Delete0 / Insert1 / Delete2 错并为一个删除；已改为完整列表先分组，再隐藏插入气球。
6. #601：右键释放在 canvas 上走了原本只应服务主键定位的 mouseup→IME 聚焦/重定位→writingAssistance.cursorMoved→菜单 invalidate。PR #796 / `5129e9bc` 加入的回调暴露了此问题。真实按住/释放鼠标已复现。

## 已保存的实现

### 原生引擎

`desktop/lowa-build/patches/apply-source-patches.py` 与 `upstream/0003-native-review-geometry-and-gutter.patch`：基于 LibreOffice core `dced3bc711d18407a2cc2400eb46e8261b663d95`。

- 只读 `AwdReviewGeometry`：JSON version1，twip，返回 pages、revision index/id/anchor/page、comment nativeid/name/anchor/page；最多每类 500 条。读现有排版，不全篇 CalcLayout，不移动活光标，不做无用完整选区矩形。
- `AwdReviewSidebarWidth`：当前视图 100% 时的 96DPI 像素宽，280 对应 4200twip，原生按 DPI/zoom 转换。0 恢复原生行为。原生预留页面审阅区，即使没有批注也可用于删除气球。
- 外部气球启用时隐藏原生批注窗口、抑制旧左侧删除绘制，仍保留 native 对象和正文插入标记；不是破坏文档内容。
- 源码补丁应用、重复幂等、旧 r3 升级、patch 等价检查已过；完整云编译完成。**新引擎尚未启动实测。**

### Worker 与 UI

`office_thread.js`：原生能力检测，无能力则保留旧引擎原生批注及正文标记，不扫描、不留空栏；原生坐标加 viewport 映射；metadata 按文档/版本缓存；删除气球显示切换不写 ShowAnnotations。

修订一次派发，用层 fingerprint 或数量变化判断成功，绝不自动重试；修改命令携带完整目标快照、稳定 ID、documentSeq，允许无关全局变化，拒绝目标内容已变/文档已替换。评论 update/delete/resolve 同样比较完整内容/作者/时间/resolved/完整 anchorText；goto 用 ID+文档围栏。列表上限 500 且有真实容量提示。

评论以 Name 或 `postit:<decimal ParaId>` 定位，不写 Name。原生 `.uno:InsertAnnotation` 窄拦截器：空内容请求先转宿主已有输入框，带 Text 的调用原样转发。Ctrl/Cmd+Alt+C 同样进入宿主。拦截器无法注册则退回原生。

`zetaOfficeReviewBalloons.js`：使用原生页面审阅区，无固定独立栏/标题；按页堆叠与连接；长内容卡片内滚动，极端拥挤时该页气球区可滚动；普通滚轮转交正文；按 key 更新卡片以保护点击；仅插入/无批注时释放原生空白区。`LibreOfficeEditor.vue` 的 ReviewPanel 改绝对定位概览，避免缩小画布；宿主批注入口有新文档/异步响应围栏。

## 两个必须先修的已知未完成项

### #600 IME：补丁存在明确边界缺陷

`zetaOfficeImeOverlay.js` 已改为遇到已确认 input 就立即提交，不等待迟到的 compositionend，并按文本去重。此前 50 条测试通过，但后来独立复核找到：组合期间非组合的**删除／空 input** 会误设 `compositionCommitted=true`，抑制随后非空 compositionend，可能丢字。

建议下一步仅将“非空插入 input”视作提前确认；保持取消组合、后续标点及重复尾随事件行为。新增 3 个回归保存在 `tests/revision-view/ime-composition.test.mjs:127` 起，**暂停时尚未运行且生产代码未修**。不能照旧的 50 通过宣称当前版本全绿。

持久真实引擎测试 `tests/lowa-e2e/ime-commit.mjs` 已写、检查语法，尚未跑新构建：常规 CDP 中文确认、先 input 后迟到 end、all/balloons 模式、只插一次、<1s、点击前新字像素已变化。`LOWA_IME_FIXTURE` 可指定私密副本；`LOWA_REQUIRE_NATIVE_REVIEW=1` 要求真 native 能力。合成事件顺序证明代码缺陷，**没有捕获真实搜狗 OS 事件顺序**。

### #601 右键：首次已改善，第二次仍失败

当前补丁：IME mouseup 跳过非主键和 macOS Ctrl-click；HTML 菜单接管时 focus；Escape 加 stopPropagation。首次真实右键按住→释放菜单保持、点击查询可用、选区保持已过。**第二次打开同一菜单，native 选区在第二次 Escape 之前已被清空**，真实回归仍失败；最新 stopPropagation 改动没有解决，需评估/删除，不能盲目保留。

日志 `/tmp/menu-601-before.log`、`/tmp/menu-601-propagation.log`；临时前后打包 `/tmp/awd-menu-601-before`、`/tmp/awd-menu-601-after`。测试 `tests/lowa-e2e/context-menu.mjs` 使用真实按住/释放，不只是 mouse.click；还要补过菜单第二次打开、Escape、外部点击和仅 native Qt 菜单。21 条 unit 曾过（最终 stopPropagation 修改前）。

## 构建产物、环境和费用

新引擎本地目录：`/Users/zewei/.cache/aiworkdeck/lowa/native-review-587-20260911`。四个文件必须配套：`soffice.js`、`soffice.wasm`、`soffice.data`、`soffice.data.js.metadata`；目录里的 `sha256.txt` 是核对依据，不能混用 r4 文件。另存压缩件、`build-evidence.tar.gz` 和 `evidence/` 诊断日志。最终下载及云资源释放结果见同目录 `HANDOFF_STATUS.json`。

用户已授权大陆阿里云按量 64核/128GiB 临时构建，18.96964元/小时，预算50元。构建 05:29:06 UTC 开始，06:05:00 UTC 完成。暂停时取回产物并主动释放资源；**不要直接重新买机器**，先看状态文件及基础设施总表。API 凭据仍在原位置，不写入代码/文档。若新原生运行有缺陷，需要再安排编译条件；勿在生产机编译。

安装中的旧 r4 引擎：`/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/lowa`。新构建尚未发布到版本路径 `24.2.8-zhcn-r5`；先验证，之后读 `deploy/publish-lowa-engine.sh`，按新版本路径发布并核验两个源站，再更新 `.github/workflows/desktop-build.yml` LOWA_BASE_URL。禁止覆盖 r4。

依赖 frontend/node_modules、desktop/node_modules 链接至主工作目录。`npm run build:zetaoffice` 会清空 dist/zetaoffice 内引擎及字体链接；构建后重建 lowa→外部目录，四个 cjk 字体指向安装 app 的同名字体。浏览器测试用 `LOWA_ENGINE_DIR` 指向新引擎。

自建 dev:h5 5177 已因暂停停止；下次需要重新启动。用户现有 backend 是 **5269**（已初始化），不是 9696；9799 属于其他工作，勿动。所有本地测试引擎已停。不要直接跑会操作全局配置/剪贴板的整套 desktop-e2e/run.mjs；新增 desktop-e2e/review.mjs 使用自己的测试项目、独立 Electron，并只清理自身。

## 验证记录及继续顺序

曾通过：73 项 review/lifecycle/ReviewPanel，37 LOWA unit，16 ReviewPanel；emits/locales；build:h5/build:zetaoffice。但这些在最终 IME/菜单补丁之前，**当前提交不保证测试全过**。旧引擎真实 revision-snapshot/comment-snapshot/reject-once 测试通过；legacy compatibility 回退实测过。

`native-comment-dispatch.mjs` 在旧引擎桥接测试中**模拟 external flag**；虽实际验证了 UNO 新批注 ID/独立更新删除，不能当作新原生布局验证。还需在真新引擎为两条新建无名批注检查不同原生锚点/卡片。

接手按以下顺序，真实引擎测试串行运行，避免多个实例造成时间失真：

1. 先修上面 IME 明确边界与菜单二次选择丢失；跑对应 unit 和真实交互回归。
2. 最终重新 build:zetaoffice/build:h5；重建引擎/字体链接。启新引擎验证 `AwdReviewGeometry`/页面区实际可用，DPR1/2、80%/100% 缩放、删除气球/插入正文、批注坐标、连接线、滚动、仅插入无空栏。
3. `word-review.mjs`（`LOWA_E2E_DPR=1`、2）、`reject-once.mjs`、`revision-snapshot.mjs`、`comment-snapshot.mjs`；长删除、同页10长批注溢出、跨页不挤占、保存重开、表格分组回归。
4. `ime-commit.mjs` 真新能力开启，生成夹具及本地私密副本；`context-menu.mjs` 全部断言通过。真实搜狗与主机 Electron 仍需实际体验确认。
5. `review-performance.mjs`：真布局最大250ms/滚动往返750ms、零反向 hit-test、metadata复用、选区与修订不被测量改变；可 `REVIEW_FIXTURE` 指定私密副本。尚未在新引擎运行，不要为了绿灯放宽阈值。
6. 新 `tests/desktop-e2e/review.mjs`：宿主概览不缩画布、工具栏切换、批注表单空值验证及输入保存、原生连接位置；已写仅语法检查，未执行。然后执行完整 native 回归（此前约548项），最终 locales/emits 检查。
7. 全部通过后再版本化发布引擎、改 CI、开 PR/构建可安装版本；遵循仓库维护者流程，不自行覆盖用户原窗口。看板交付记录再转待复测，别提前完成。

详细前一段上下文保存在本机 `evidence/native-review-continuation-587.md`，部分状态较旧，以本文件与最终状态 JSON 为准。共享方法纠错有独立克隆 `/tmp/word-review-shared-586`、PR `zeweihan/zeweiandmasterC#4`：旧 inverse geometry recipe 应标 deprecated，新 snapshot recipe 只写已验证范围。该克隆暂停时仍有未提交改动，不能当作共享分支已接入；继续时按 CONTRIBUTING 提交，公共副本只读。
