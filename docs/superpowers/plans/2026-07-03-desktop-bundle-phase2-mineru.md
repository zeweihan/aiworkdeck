# 桌面版三服务打包 Phase 2：mineru-service 进包 + ModelManager — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文档解析（MinerU）随桌面安装包分发：依赖进包、模型首启在「组件管理」页一键下载（进度可见），下载后自动/手动拉起本地服务；云端兜底默认关闭。

**Architecture:** 复用 Phase 1 全部基建——`prepare-python-service.js` 烙 mineru 依赖（无自有源码，纯 pip 包）、ServiceManager 描述符拉起 `mineru-api`、`MINERU_LOCAL_URL`/`MINERU_FORCE_CLOUD` 经 spawn env 注入 pptx-service（Java 后端零改动）。新增 ModelManager（Electron main）：**不自研下载器**，spawn 官方 `mineru-models-download`（ModelScope SDK 自带断点续传），解析 stdout 进度经 IPC 推给渲染层；前端 admin.vue 新增「组件管理」分区。

**Tech Stack:** 同 Phase 1（python-build-standalone 3.11、node:test、uni-app/Vue2 语法的 admin.vue）+ mineru[core] 2.7.x（内含 FastAPI 服务与模型下载 CLI）。

## Global Constraints

- 沿用 Phase 1 全部约束：CommonJS、无新 npm 运行时依赖、python 统一 3.11、mac 仅 arm64、`docs/` 提交须 `git add -f`、commit 格式 `type(scope): 中文 / english`。
- **pptx-service 业务代码零改动**（云端兜底开关走 spawn env，不改 config.py 默认值——Docker 部署形态行为不变）。
- **Java 后端零改动**（mineru 只被 pptx-service 消费，已确认 backend/src 无 mineru 绑定）。
- mineru 模型（约 3GB）**绝不进安装包也绝不进 CI**：CI 冒烟只验证服务二进制可启动（无模型时 `/docs` 可 200；若 mineru-api 启动即加载模型则降级为 `import mineru` + CLI `--help` 校验，Task 1 本地先验证哪种可行）。
- 模型与数据目录：模型 `~/.aiworkdeck/models/mineru/`，服务临时目录 `~/.aiworkdeck/mineru/`，日志沿用 `~/.aiworkdeck/logs/mineru-service.log`。
- **与设计文档的已声明偏离**：ModelManager 不自研「断点续传+sha256」（设计 §2.3 原文），改为复用官方 `mineru-models-download`（ModelScope/HF SDK 原生支持续传与校验）；确认框+进度条落在「组件管理」页而非全局拦截弹窗。Task 6 同步修订设计文档。

## 前置校证（Task 1 内完成，两处不确定点）

- **P1**：`mineru-api`/`mineru-models-download` 是 console-script，`pip --target` 装出的 `lib/bin/*` shim shebang 不可靠 → 必须找到等价 module 入口（形如 `python -m mineru.cli.fast_api` / `python -m mineru.cli.models_download`），从烙出的 lib 里读 `mineru-*.dist-info/entry_points.txt` 确认确切模块与函数名。
- **P2**：模型缓存目录环境变量——MinerU 2.x 经 modelscope/huggingface SDK 下载，重定向用 `MODELSCOPE_CACHE` + `HF_HOME`（两个都设，指向 `~/.aiworkdeck/models/mineru/`）；从 lib 源码 grep `MINERU_MODEL_SOURCE|MODELSCOPE_CACHE|HF_HOME|snapshot_download` 确认，并确认 mineru 自身读取模型路径的 config（`mineru.json`/env）如何指向缓存。**以实际源码为准，下述代码中的 env 名在此步锁定。**

**前置校证结论（2026-07-03，mineru 2.7.6 实测）：**
- P1 服务入口：`python -m mineru.cli.fast_api --host 127.0.0.1 --port <port>`（fast_api.py:450 有 main guard）。下载入口：`python -c "from mineru.cli.models_download import download_models; download_models()" -s modelscope -m pipeline`（click 命令解析 argv，双参齐全即非交互）。
- P2 模型落盘：`MODELSCOPE_CACHE=<models>/mineru` + `HF_HOME=<models>/mineru/hf`（snapshot_download 走 SDK 缓存）；配置文件 `MINERU_TOOLS_CONFIG_JSON=<models>/mineru/mineru.json`（源码是 `os.path.join(home, 值)`，传绝对路径即生效，下载与运行两侧同一 env）。注意 `configure_model` 会从 gcore.jsdelivr.net 拉模板 json（下载步骤本来在线，可接受）。
- 冒烟形态：**无模型时 mineru-api 约 4s 可起、/docs 返回 200**（模型解析时懒加载）——CI 用完整拉起 + /docs 校验。
- lib 体积实测 **1.5GB**（未压缩，mac-arm64，含 torch 2.12.1）。

---

### Task 1: mineru 依赖锁 + 烙制 + 本地拉起验证（风险前置）

**Files:**
- Create: `mineru-service/requirements.in`（一行：`mineru[core]==<当前最新 2.7.x，锁定>`）
- Create: `mineru-service/requirements.lock`
- Modify: `desktop/scripts/prepare-python-service.js`（`--src` 改为可选：无源码服务只装依赖）

**Interfaces:**
- Produces: `desktop/bundled/<os>-<arch>/pysvc/mineru-service/lib`（无 `app/`）；确认的 module 入口与模型 env 名（写进本文件「前置校证结论」小节，供 Task 3/4 引用）。

- [ ] **Step 1: 生成锁文件**

```bash
cd "…/mineru-service"
echo 'mineru[core]==2.7.*' > requirements.in   # 用 uv pip compile 锁到具体版本
uv pip compile requirements.in -o requirements.lock --python-version 3.11
grep -cE "^(mineru|torch|transformers)" requirements.lock   # 预期 ≥3
```

- [ ] **Step 2: prepare-python-service.js 支持无源码服务**

`copyAppSource` 调用处改为：

```js
  if (args.src) copyAppSource(path.resolve(args.src), appDir)
```

并把 `parseArgs` 必填清单中的 `'src'` 移除（`['service', 'requirements', 'out']`）。

- [ ] **Step 3: 本地烙制（mac-arm64，后台跑，依赖树含 torch 较大）**

```bash
cd "…/desktop"
node scripts/prepare-python-service.js \
  --service mineru-service \
  --requirements ../mineru-service/requirements.lock \
  --out bundled/mac-arm64
du -sh bundled/mac-arm64/pysvc/mineru-service/lib   # 记录体积，写进 PR
```

- [ ] **Step 4: 前置校证 P1/P2**

```bash
LIB=bundled/mac-arm64/pysvc/mineru-service/lib
cat $LIB/mineru-*.dist-info/entry_points.txt        # → mineru-api / mineru-models-download 的 module:func
grep -rn "MODELSCOPE_CACHE\|HF_HOME\|MINERU_MODEL_SOURCE" $LIB/mineru/ | head
```
把结论（确切 `python -m` 入口、模型目录 env 组合）追加到本计划「前置校证结论」小节并据此校对 Task 3/4 代码。

- [ ] **Step 5: 本地拉起验证（无模型）**

```bash
PYTHONPATH=$LIB PORT 任选：
MINERU_DEVICE_MODE=cpu MINERU_MODEL_SOURCE=modelscope \
  ../python/bin/python3.11 -m <P1入口> --host 127.0.0.1 --port 8099 &
sleep 8 && curl -sf http://127.0.0.1:8099/docs > /dev/null && echo DOCS_OK
```
若无模型时启动失败 → CI 冒烟降级为 `python -c "import mineru"` + `-m <入口> --help`，在 Task 5 用降级版。

- [ ] **Step 6: 提交**

```bash
git add mineru-service/requirements.in mineru-service/requirements.lock desktop/scripts/prepare-python-service.js
git commit -m "feat(desktop): mineru 依赖锁 + 烙制脚本支持无源码服务 / mineru requirements lock & sourceless bundling"
```

---

### Task 2: ModelManager（Electron main）+ 单元测试

**Files:**
- Create: `desktop/main/services/model-manager.js`
- Test: `desktop/tests/model-manager.test.js`
- Modify: `desktop/package.json`（test script 追加该文件：`node --test tests/service-manager.test.js tests/model-manager.test.js`）

**Interfaces:**
- Produces:
  - `createModelManager({ dataDir, resourcesPath, packaged, onProgress })` → `{ status(), download(id), cancel(id), remove(id), isInstalled(id) }`
  - 组件注册表（本期一项）：`{ id: 'mineru-models', name: '文档解析模型（MinerU）', sizeHint: '约 3GB' }`
  - 状态机：`absent | downloading | installed | error`；`installed` 判据 = `<models>/mineru/.aiworkdeck-complete` 标记文件（下载进程 exit 0 后写入）
  - `onProgress({ id, phase, percent?, message })` 回调——main.js 桥接到 `webContents.send('checkba:model-progress', …)`

**核心实现要点（完整逻辑，非伪码）：**

```js
// download('mineru-models')：
// 1. spawn(pyBin, ['-m', '<P1下载入口>', '-s', 'modelscope', '-m', 'pipeline'], {
//      env: { ...process.env, PYTHONPATH: mineruLib, MODELSCOPE_CACHE: modelsDir, HF_HOME: modelsDir,  // ← P2 校证后定稿
//             MINERU_MODEL_SOURCE: 'modelscope' },
//      cwd: modelsDir })
// 2. stdout/stderr 逐行解析：匹配 /(\d{1,3})%/ 提取百分比，无百分比行则透传为 message；
//    每 500ms 节流调用 onProgress
// 3. exit 0 → 写标记文件 → onProgress({phase:'done'})；非 0 → onProgress({phase:'error', message: tail})
// 4. cancel: child.kill('SIGTERM')；remove: rmSync(modelsDir 下 mineru 子树) + 删标记
```

- [ ] **Step 1: 写失败测试**（下载进程用 `node -e` 假脚本模拟：打印 `10%…100%` 后 exit 0；断言状态迁移 absent→downloading→installed、进度回调次数≥1、cancel 后状态回 absent、remove 清目录）——描述符里 `pyBin/args` 必须可注入（测试传 `spawnSpec` 覆盖），生产默认走 mineru lib。
- [ ] **Step 2: 跑测试确认失败** `cd desktop && npm test`
- [ ] **Step 3: 实现 model-manager.js**
- [ ] **Step 4: 测试转绿**
- [ ] **Step 5: 提交** `feat(desktop): ModelManager——组件状态机 + 官方下载器封装 + 进度节流 / model manager`

---

### Task 3: mineru 描述符 + IPC + preload 桥

**Files:**
- Create: `desktop/main/services/mineru-service.js`
- Modify: `desktop/main/services/pptx-service.js`（spawnEnv 注入 `MINERU_LOCAL_URL=http://127.0.0.1:<ports['mineru-service']>` 与 `MINERU_FORCE_CLOUD: process.env.CHECKBA_MINERU_FORCE_CLOUD || '0'`）
- Modify: `desktop/main/main.js`（注册描述符 + ModelManager 实例化 + 5 个 ipcMain.handle + 进度桥接）
- Modify: `desktop/preload/preload.js`（新增 `model` 与 `services` namespace）

**Interfaces:**
- mineru 描述符：`name:'mineru-service'`；**条件 eager**：`eager: true` + `enabled: (ctx) => ctx.packaged && modelManager.isInstalled('mineru-models')`（模型未装则 startEager 直接跳过，不报错）；dev 态沿用「只复用 8001 不 spawn」；`startTimeoutMs: 180000`（模型加载慢）；spawn env：`PYTHONPATH=mineruLib, MODELSCOPE_CACHE/HF_HOME=modelsDir（P2 定稿）, MINERU_DEVICE_MODE=cpu, MINERU_MODEL_SOURCE=modelscope`；args `['-m','<P1入口>','--host','127.0.0.1','--port',String(port)]`。
- IPC（沿用 checkba:* 惯例）：`checkba:model-status`、`checkba:model-download`、`checkba:model-cancel`、`checkba:model-remove`、`checkba:service-ensure`（`{name}` → `services.start(name)`，组件页「启用」按钮用）；推送 `checkba:model-progress`。
- preload 桥：
  ```js
  model: {
    status: () => ipcRenderer.invoke('checkba:model-status'),
    download: (id) => ipcRenderer.invoke('checkba:model-download', { id }),
    cancel: (id) => ipcRenderer.invoke('checkba:model-cancel', { id }),
    remove: (id) => ipcRenderer.invoke('checkba:model-remove', { id }),
    onProgress: (handler) => { /* on/removeListener 模式，返回退订函数 */ }
  },
  services: { ensure: (name) => ipcRenderer.invoke('checkba:service-ensure', { name }) }
  ```

- [ ] **Step 1: 写 mineru-service.js（对照 pptx-service.js，含 dev-reuse-8001 逻辑）**
- [ ] **Step 2: pptx-service.js 注入 mineru env（两行）**
- [ ] **Step 3: main.js 注册 + IPC + 进度桥接（模型下载完成时若 mineru 未运行则自动 `services.start('mineru-service')`）**
- [ ] **Step 4: preload.js 桥**
- [ ] **Step 5: `npm test` 回归 + `node --check` 全部改动文件**
- [ ] **Step 6: 提交** `feat(desktop): mineru-service 描述符 + 模型 IPC 桥——条件 eager/动态端口注入 pptx / mineru descriptor & model IPC`

---

### Task 4: 前端「组件管理」+ 向导合规文案

**Files:**
- Modify: `frontend/src/pages/admin/admin.vue`（navItems 加 `{key:'components', label:'组件管理'}`，仅 `window.checkbaDesktop` 存在时显示；新增 scroll-view 分区）
- Modify: `frontend/src/pages/wizard/wizard.vue`（数据流向说明补一句：「文档解析与语音合成默认在本机完成；本地组件首次使用需一次性下载」）

**组件管理分区（完整交互）：**
- 卡片：组件名/说明/体积提示 + 状态徽标（未下载/下载中 x%/已就绪/运行中/错误）
- 按钮：未下载→「下载（约 3GB）」点击后二次确认 `uni.showModal`；下载中→「取消」；已就绪→「启用」（调 `services.ensure`）+「删除」；运行中→仅展示
- `onProgress` 订阅在 `onUnload` 退订；非桌面环境整个分区不渲染
- 状态数据源：`checkbaDesktop.model.status()` 返回 `[{id,name,state,percent}]`，「运行中」由 status 结果里 `serviceRunning: true` 标注（main 侧用 `isPortOpen(ports['mineru-service'])` 判断）

- [ ] **Step 1: admin.vue 分区 + 交互**
- [ ] **Step 2: wizard.vue 文案一句**
- [ ] **Step 3: 前端构建冒烟 `cd frontend && npm run build:h5`（uni-app 编译过 = 语法关）**
- [ ] **Step 4: 提交** `feat(frontend): 组件管理页——模型下载进度/启用/删除 + 向导数据流向补文档解析 / component manager UI`

---

### Task 5: CI 集成

**Files:**
- Modify: `.github/workflows/desktop-build.yml`

**步骤（对照 Phase 1 pptx 的四处）：**
- mac/win 各加 `Bundle mineru-service`（复用 prepare-python-service.js，无 `--src`；**注意 win runner 磁盘**：mineru lib 含 torch 约 2-3GB，烙完 `du -sh` 打日志）
- mac/win 各加 `Smoke test bundled mineru-service`：按 Task 1 Step 5 验证可行的形态（/docs 200 或 import+--help 降级版）
- extraResources 无需改（`pysvc/` 整目录已进包）
- 冒烟通过后照常打包公证——**预期公证再遇古董二进制的概率高**（torch/opencv 生态），若被拒：按 Phase 1 flac 先例，在 prune() 剔除报告点名的文件并记录

- [ ] **Step 1: yml 四处修改 + node 结构粗检**
- [ ] **Step 2: 提交推送，开 PR，盯 CI**（公证被拒 → 修 prune → 重推，循环至绿）

---

### Task 6: 文档对齐 + 合并

- [ ] **Step 1: 设计文档修订**：§2.3 ModelManager 改为「复用官方 mineru-models-download（SDK 自带续传/校验），进度解析+IPC 自研」；§3 Phase 2 勾掉；风险表补「公证 vs torch 生态二进制」条目与对策
- [ ] **Step 2: desktop/README.md 打包步骤补 mineru 烙制命令；组件管理/模型目录说明**
- [ ] **Step 3: `git add -f docs/...` 提交；CI 全绿后按交付流程合并 master、删远端分支**

---

## Self-Review 结论（已执行）

1. **设计 §3 Phase 2 覆盖**：mineru venv 进包（T1/T5）、ModelManager 下载/续传/校验/进度 IPC（T2/T3，续传校验借官方 CLI——已声明偏离）、前端确认框+进度+组件管理页（T4）、云端兜底默认关闭（T3 spawn env，零代码改动优于原设想）、冒烟测试跳过模型（T5+T1 前置验证）。✔
2. **占位符**：`<P1入口>`/`<P2 env>` 是**有意的前置校证锚点**（Task 1 Step 4 锁定后回填），非未决设计；其余无 TBD。✔
3. **一致性**：ports key `'mineru-service'`、IPC 频道名、标记文件路径在 T2/T3/T4 间一致；`services.ensure` 与 ServiceManager.start(name) 签名吻合。✔

风险提醒：mineru lib 体积（torch~2GB）使安装包逼近设计预估的 2GB 上限，若 dmg/exe 超 2.5GB 需在 PR 里给出实际数并评估 lib 裁剪（torchvision test 数据、triton 等 linux-only 包是否被 lock 排除）。
