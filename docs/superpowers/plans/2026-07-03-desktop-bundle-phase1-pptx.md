# 桌面版三服务打包 Phase 1：ServiceManager + pptx-service 进包 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `desktop/main/backend.js` 泛化为描述符驱动的 ServiceManager，并把 pptx-service（Python/Flask）连同独立 Python 运行时打进桌面安装包，CI 三平台冒烟验证。

**Architecture:** 沿用已验证的「CI 预烙运行时 → extraResources 进包 → Electron spawn 管理」模式（见 docs/DESKTOP_LOCAL_BUNDLE_PLAN.md §2）。ServiceManager 持有服务描述符列表，统一负责端口分配/复用检测/候选命令轮试/日志落盘/优雅停止；Java 后端迁入为首个描述符（行为不变，CI 既有冒烟测试作回归门），pptx-service 为第二个描述符。

**Tech Stack:** Electron 30（main process，CommonJS）、node:test（Node 18+ 内置，desktop 首次引入测试）、python-build-standalone 3.11（astral-sh）、pip `--target` 交叉安装、Flask/SQLite（pptx-service 现状）、GitHub Actions（macos-latest + windows-latest）。

## Global Constraints

- 桌面 main process 代码为 CommonJS（`require`），无构建步骤，禁止引入新 npm 运行时依赖（node:test 是内置模块，不算）。
- Python 运行时统一 **3.11**（python-build-standalone release `20250409`，`cpython-3.11.12`；pptx-service `requires-python = ">=3.10"` 兼容）。
- Java 后端行为不得变化：默认端口 9696、`CHECKBA_BACKEND_PORT` 覆盖、dev 态 `prod` profile / 打包态 `desktop` profile、捆绑 JRE 失败后回退系统 JDK、复用已开端口。CI 既有 “Smoke test bundled backend” 步骤必须原样通过。
- pptx-service 业务代码除 `PPTX_DATA_DIR` 数据目录支持外不做任何修改（设计非目标：不重写业务逻辑）。
- 本机构建后端 jar 必须用 **JDK 21**（默认 JDK 25 会 SIGBUS，见仓库既有约定）。
- 新文档一律 `git add -f`（`docs/` 在 .gitignore 中，仓库惯例强制添加）。
- 提交信息格式沿用仓库惯例：`type(scope): 中文说明 / english summary`。
- **Phase 1 有意偏离设计文档一处**：pptx-service 采用 **eager 启动**而非 lazy（设计文档 §2.1 原写 lazy）。原因：AI PPT 的触发方在 Java 后端 agent 工具循环内（`PptxTools` → `PptxServiceClient`），渲染进程无可靠拦截点做 ensure；且 pptx-service 是轻量 Flask+SQLite（秒级启动、~100MB 内存），eager 成本可忽略。lazy 机制（IPC ensure + 前端确认框）随 Phase 2 的 mineru（真正的重量级）一起落地。Task 7 同步修订设计文档。

---

### Task 1: ServiceManager 核心 + 单元测试

**Files:**
- Create: `desktop/main/services/service-manager.js`
- Test: `desktop/tests/service-manager.test.js`
- Modify: `desktop/package.json`（scripts 加 `"test": "node --test tests/"`）

**Interfaces:**
- Produces（后续 Task 依赖的确切签名）:
  - `createServiceManager(ctx)` → `ServiceManager`；`ctx = { packaged, resourcesPath, dataDir, projectRoot }`
  - `ServiceManager.register(descriptor)`：descriptor 见下方注释块
  - `ServiceManager.allocatePorts()` → `Promise<void>`（填充 `this.ports[name]`）
  - `ServiceManager.startEager()` → `Promise<{ [name]: { ok, reused?, error? } }>`
  - `ServiceManager.start(name)` / `stop(name)` / `restart(name)` / `stopAll()`
  - 辅助导出：`isPortOpen(port, host?, timeoutMs?)`、`findFreePort()`

- [ ] **Step 1: 写失败的测试**

`desktop/tests/service-manager.test.js`：

```js
const test = require('node:test')
const assert = require('node:assert')
const path = require('path')
const { createServiceManager, findFreePort, isPortOpen } = require('../main/services/service-manager')

// 用 node 自身起一个最小 HTTP 服务当"假服务"，验证 spawn/端口等待/停止全链路
const FAKE_SERVICE = `
  const http = require('http');
  const port = Number(process.env.PORT);
  http.createServer((req, res) => res.end('ok')).listen(port, '127.0.0.1');
`

function fakeDescriptor(overrides) {
  return Object.assign({
    name: 'fake',
    eager: true,
    logName: 'fake',
    port: async () => findFreePort(),
    startTimeoutMs: () => 10000,
    commands: (ctx) => [{
      cmd: process.execPath,
      args: ['-e', FAKE_SERVICE],
      env: { ...process.env, PORT: String(ctx.ports.fake) },
      cwd: ctx.dataDir
    }]
  }, overrides)
}

test('findFreePort returns a usable port', async () => {
  const port = await findFreePort()
  assert.ok(port > 0)
  assert.strictEqual(await isPortOpen(port), false)
})

test('start/stop lifecycle: spawns, waits for port, stops', async () => {
  const mgr = createServiceManager({
    packaged: false,
    resourcesPath: null,
    dataDir: require('os').tmpdir(),
    projectRoot: path.join(__dirname, '..')
  })
  mgr.register(fakeDescriptor())
  await mgr.allocatePorts()
  const res = await mgr.start('fake')
  assert.strictEqual(res.ok, true)
  assert.strictEqual(res.reused, false)
  assert.strictEqual(await isPortOpen(mgr.ports.fake), true)
  await mgr.stop('fake')
  assert.strictEqual(await isPortOpen(mgr.ports.fake), false)
})

test('reuses already-open port without spawning', async () => {
  const http = require('http')
  const server = http.createServer((req, res) => res.end('ok'))
  const port = await findFreePort()
  await new Promise((r) => server.listen(port, '127.0.0.1', r))
  try {
    const mgr = createServiceManager({
      packaged: false, resourcesPath: null,
      dataDir: require('os').tmpdir(), projectRoot: path.join(__dirname, '..')
    })
    mgr.register(fakeDescriptor({ port: async () => port }))
    await mgr.allocatePorts()
    const res = await mgr.start('fake')
    assert.strictEqual(res.reused, true)
  } finally {
    server.close()
  }
})

test('falls through failed candidate to next command', async () => {
  const mgr = createServiceManager({
    packaged: false, resourcesPath: null,
    dataDir: require('os').tmpdir(), projectRoot: path.join(__dirname, '..')
  })
  mgr.register(fakeDescriptor({
    commands: (ctx) => [
      // 第一个候选立刻退出（崩溃路径）
      { cmd: process.execPath, args: ['-e', 'process.exit(1)'], env: process.env, cwd: ctx.dataDir },
      { cmd: process.execPath, args: ['-e', FAKE_SERVICE], env: { ...process.env, PORT: String(ctx.ports.fake) }, cwd: ctx.dataDir }
    ]
  }))
  await mgr.allocatePorts()
  const res = await mgr.start('fake')
  assert.strictEqual(res.ok, true)
  await mgr.stopAll()
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd desktop && node --test tests/
```
预期：FAIL，`Cannot find module '../main/services/service-manager'`

- [ ] **Step 3: 实现 service-manager.js**

`desktop/main/services/service-manager.js`（isPortOpen/sleep 逻辑从 `backend.js:5-29` 原样搬移）：

```js
const path = require('path')
const net = require('net')
const fs = require('fs')
const { spawn } = require('child_process')

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function isPortOpen(port, host = '127.0.0.1', timeoutMs = 250) {
  return new Promise((resolve) => {
    const socket = new net.Socket()
    let done = false
    const finish = (ok) => {
      if (done) return
      done = true
      try { socket.destroy() } catch (e) { /* ignore */ }
      resolve(ok)
    }
    socket.setTimeout(timeoutMs)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(port, host)
  })
}

// 向内核要一个空闲端口（listen 0 再立即释放）。存在"释放后被抢"的窗口，
// 概率极低且仅影响单机自用端口，可接受（见设计文档 §2.1 端口策略）
function findFreePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = s.address().port
      s.close(() => resolve(port))
    })
    s.once('error', reject)
  })
}

/**
 * 服务描述符（descriptor）契约：
 * {
 *   name: string                       // 唯一名，也是 ports 表的 key
 *   eager: boolean                     // startEager() 是否拉起
 *   logName: string                    // 打包态日志文件名（<dataDir>/logs/<logName>.log）
 *   enabled?: (ctx) => boolean         // 缺省 true；false 则 start() 返回 {ok:false, disabled:true}
 *   port: (ctx) => number|Promise<number>   // 分配端口（可读 env、可 findFreePort）
 *   startTimeoutMs: (ctx) => number
 *   prepare?: (ctx) => Promise<void>   // spawn 前置（dev 构建 jar / 打包态跑 DB 迁移）
 *   commands: (ctx) => [{cmd, args, env, cwd}]           // 候选命令，按序轮试
 *   moreCommandsAfterFailure?: (ctx) => [{...}]          // 首个候选失败后追加（系统 JDK 回退）
 * }
 * ctx = { packaged, resourcesPath, dataDir, projectRoot, ports }
 */
class ServiceManager {
  constructor(ctx) {
    this.ctx = { ...ctx, ports: {} }
    this.ports = this.ctx.ports
    this.descriptors = new Map()
    this.procs = new Map()
  }

  register(descriptor) {
    this.descriptors.set(descriptor.name, descriptor)
  }

  async allocatePorts() {
    for (const d of this.descriptors.values()) {
      this.ports[d.name] = await d.port(this.ctx)
    }
  }

  logStreamFor(d) {
    if (!this.ctx.packaged) return null
    const dir = path.join(this.ctx.dataDir, 'logs')
    fs.mkdirSync(dir, { recursive: true })
    return fs.createWriteStream(path.join(dir, d.logName + '.log'), { flags: 'a' })
  }

  async start(name) {
    const d = this.descriptors.get(name)
    if (!d) throw new Error(`unknown service: ${name}`)
    if (d.enabled && !d.enabled(this.ctx)) return { ok: false, disabled: true }
    const port = this.ports[name]

    // 已有实例在跑：直接复用（与 BackendManager 语义一致）
    if (await isPortOpen(port)) return { ok: true, reused: true }

    if (d.prepare) await d.prepare(this.ctx)
    if (await isPortOpen(port)) return { ok: true, reused: true }
    if (this.procs.get(name)) await this.stop(name)

    const timeoutMs = d.startTimeoutMs(this.ctx)
    const logStream = this.logStreamFor(d)
    const candidates = [...d.commands(this.ctx)]
    let triedFallback = false

    for (let i = 0; i < candidates.length; i++) {
      const spec = candidates[i]
      const stdio = logStream ? ['ignore', 'pipe', 'pipe'] : 'inherit'
      const proc = spawn(spec.cmd, spec.args, { cwd: spec.cwd, env: spec.env, stdio })
      this.procs.set(name, proc)
      if (logStream) {
        proc.stdout.pipe(logStream)
        proc.stderr.pipe(logStream)
      }

      // 等"端口就绪"或"进程提前退出"，谁先到算谁（搬自 backend.js:165-187）
      // eslint-disable-next-line no-await-in-loop
      const result = await this.waitStartOrExit(proc, port, timeoutMs)
      if (result === 'started') return { ok: true, reused: false, cmd: spec.cmd }
      if (result === 'timeout') {
        // eslint-disable-next-line no-await-in-loop
        try { await this.stop(name) } catch (e) { /* ignore */ }
      } else {
        this.procs.delete(name)
      }

      if (!triedFallback && d.moreCommandsAfterFailure) {
        triedFallback = true
        for (const c of d.moreCommandsAfterFailure(this.ctx)) {
          if (!candidates.some((x) => x.cmd === c.cmd)) candidates.push(c)
        }
      }
    }
    throw new Error(`${name} failed to start on port ${port}`)
  }

  waitStartOrExit(proc, port, timeoutMs) {
    return new Promise((resolve) => {
      let settled = false
      const finish = (v) => {
        if (settled) return
        settled = true
        resolve(v)
      }
      proc.once('exit', () => finish('exited'))
      proc.once('error', () => finish('exited'))
      const start = Date.now()
      const poll = async () => {
        while (!settled && Date.now() - start < timeoutMs) {
          // eslint-disable-next-line no-await-in-loop
          if (await isPortOpen(port)) return finish('started')
          // eslint-disable-next-line no-await-in-loop
          await sleep(200)
        }
        finish('timeout')
      }
      poll()
    })
  }

  async startEager() {
    const results = {}
    for (const d of this.descriptors.values()) {
      if (!d.eager) continue
      try {
        // eslint-disable-next-line no-await-in-loop
        results[d.name] = await this.start(d.name)
      } catch (e) {
        results[d.name] = { ok: false, error: String(e && e.message ? e.message : e) }
      }
    }
    return results
  }

  async stop(name) {
    const p = this.procs.get(name)
    if (!p) return { ok: true }
    this.procs.delete(name)
    return new Promise((resolve) => {
      let finished = false
      const done = () => {
        if (finished) return
        finished = true
        resolve({ ok: true })
      }
      p.once('exit', () => done())
      try { p.kill('SIGTERM') } catch (e) { done(); return }
      setTimeout(() => {
        try { p.kill('SIGKILL') } catch (e) { /* ignore */ }
        done()
      }, 3000)
    })
  }

  async stopAll() {
    for (const name of [...this.procs.keys()]) {
      // eslint-disable-next-line no-await-in-loop
      await this.stop(name)
    }
    return { ok: true }
  }

  async restart(name) {
    await this.stop(name)
    return this.start(name)
  }
}

function createServiceManager(ctx) {
  return new ServiceManager(ctx)
}

module.exports = { createServiceManager, ServiceManager, isPortOpen, findFreePort }
```

- [ ] **Step 4: 在 `desktop/package.json` 的 scripts 里加测试命令**

```json
"scripts": {
  "clean": "node scripts/clean.js",
  "dev": "cross-env AIWORKDECK_DESKTOP_DEV=1 electron .",
  "start": "electron .",
  "build": "electron-builder",
  "test": "node --test tests/"
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd desktop && npm test
```
预期：4 项测试 PASS

- [ ] **Step 6: 提交**

```bash
git add desktop/main/services/service-manager.js desktop/tests/service-manager.test.js desktop/package.json
git commit -m "feat(desktop): 描述符驱动的 ServiceManager——多本地服务统一拉起/停止/端口治理 / generic local service manager"
```

---

### Task 2: Java 后端迁入 ServiceManager（行为不变）

**Files:**
- Create: `desktop/main/services/backend-service.js`
- Modify: `desktop/main/main.js`（第 3、21、1145-1152、1191-1204、1216-1228、1231-1234 行区域）
- Delete: `desktop/main/backend.js`

**Interfaces:**
- Consumes: Task 1 的 `createServiceManager` / descriptor 契约。
- Produces: `createBackendDescriptor()` → descriptor（name `'backend'`）；main.js 中全局 `services`（ServiceManager 实例）供 Task 5 注册 pptx 描述符。

- [ ] **Step 1: 写 backend 描述符**

`desktop/main/services/backend-service.js`（逻辑从 `backend.js` 逐段搬移，注释保留原意）：

```js
const path = require('path')
const os = require('os')
const fs = require('fs')
const { spawn, execFileSync } = require('child_process')

function jarPath(ctx) {
  return ctx.packaged
    ? path.join(ctx.resourcesPath, 'backend', 'backend.jar')
    : path.join(ctx.projectRoot, 'backend', 'target', 'backend-0.0.1-SNAPSHOT.jar')
}

function bundledJava(ctx) {
  return path.join(ctx.resourcesPath, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
}

// dev 态无 jar 时现场 mvn 打包（搬自 backend.js:68-81）
function buildJar(ctx) {
  return new Promise((resolve, reject) => {
    const mvnCmd = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
    const p = spawn(mvnCmd, ['-q', '-DskipTests', 'package'], {
      cwd: path.join(ctx.projectRoot, 'backend'),
      stdio: 'inherit'
    })
    p.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`mvn package failed: ${code}`))))
    p.on('error', (e) => reject(e))
  })
}

function spawnEnv(ctx) {
  const env = { ...process.env }
  // 开发态默认 prod profile，打包态默认 desktop（H2 单机库），仍可用 env 覆盖
  if (!env.SPRING_PROFILES_ACTIVE) env.SPRING_PROFILES_ACTIVE = ctx.packaged ? 'desktop' : 'prod'
  if (!env.SERVER_PORT) env.SERVER_PORT = String(ctx.ports.backend)
  // 其他本地服务的动态端口注入（Spring SystemEnvironmentPropertySource 将
  // external.pptx-service.base-url 解析为 EXTERNAL_PPTX_SERVICE_BASE_URL）
  if (ctx.ports['pptx-service']) {
    env.EXTERNAL_PPTX_SERVICE_BASE_URL = 'http://127.0.0.1:' + ctx.ports['pptx-service']
  }
  return env
}

function spec(ctx, javaCmd) {
  // 打包模式：工作目录必须可写（resources 目录只读）
  const cwd = ctx.packaged ? ctx.dataDir : path.join(ctx.projectRoot, 'backend')
  if (ctx.packaged) fs.mkdirSync(cwd, { recursive: true })
  return { cmd: javaCmd, args: ['-jar', jarPath(ctx)], env: spawnEnv(ctx), cwd }
}

// 系统 JDK 回退候选，仅打包态、捆绑 JRE 启动失败后（搬自 backend.js:190-210）
function systemJavaFallbacks() {
  const out = []
  const exe = process.platform === 'win32' ? 'java.exe' : 'java'
  if (process.platform === 'darwin') {
    for (const args of [['-v', '21'], []]) {
      try {
        const home = execFileSync('/usr/libexec/java_home', args, { encoding: 'utf8' }).trim()
        if (home) out.push(path.join(home, 'bin', exe))
      } catch (e) { /* 该版本/任意 JDK 不存在，忽略 */ }
    }
  } else {
    if (process.env.JAVA_HOME) out.push(path.join(process.env.JAVA_HOME, 'bin', exe))
    out.push(exe)
  }
  return out.filter((c, i) => out.indexOf(c) === i)
}

function createBackendDescriptor() {
  return {
    name: 'backend',
    eager: true,
    logName: 'backend',
    port: () => Number(process.env.CHECKBA_BACKEND_PORT || 9696),
    startTimeoutMs: (ctx) => (ctx.packaged ? 60000 : 15000),
    prepare: async (ctx) => {
      if (fs.existsSync(jarPath(ctx))) return
      if (ctx.packaged) throw new Error(`bundled backend jar missing: ${jarPath(ctx)}`)
      await buildJar(ctx)
    },
    commands: (ctx) => [spec(ctx, ctx.packaged ? bundledJava(ctx) : (process.platform === 'win32' ? 'java.exe' : 'java'))],
    moreCommandsAfterFailure: (ctx) => (ctx.packaged ? systemJavaFallbacks().map((j) => spec(ctx, j)) : [])
  }
}

module.exports = { createBackendDescriptor }
```

- [ ] **Step 2: 改 main.js 接线**

`desktop/main/main.js` 逐处修改：

第 3 行 `const { BackendManager } = require('./backend')` 改为：
```js
const { createServiceManager } = require('./services/service-manager')
const { createBackendDescriptor } = require('./services/backend-service')
```

第 21 行 `let backend = null` 改为：
```js
let services = null
```

第 1145-1152 行 `createBackendManager()` 整个函数替换为：
```js
function createServices() {
  // 打包模式下 jar/JRE 从 resourcesPath 解析（Epic #18 T2），数据落 ~/.aiworkdeck
  const mgr = createServiceManager({
    projectRoot: path.join(__dirname, '..', '..'),
    packaged: app.isPackaged,
    resourcesPath: process.resourcesPath,
    dataDir: path.join(app.getPath('home'), '.aiworkdeck')
  })
  mgr.register(createBackendDescriptor())
  return mgr
}
```

第 1191-1204 行 `app.whenReady` 内的后端启动段替换为：
```js
  // 桌面端启动时自动拉起本机后端（9696）
  services = createServices()
  services
    .allocatePorts()
    .then(() => services.startEager())
    .then((results) => {
      createMainWindow()
      const b = results.backend
      if (b && !b.ok) {
        // 后端失败也允许打开 UI（方便你调试），但会提示错误
        try {
          if (mainWindow) mainWindow.webContents.send('checkba:backend-status', { ok: false, message: b.error || 'backend failed' })
        } catch (err) { /* ignore */ }
      }
    })
```

第 1216-1228 行退出清理段中 `backend.stop()` 改为：
```js
  if (services) {
    try {
      await services.stopAll()
    } catch (e) { /* ignore */ }
    services = null
  }
```
（保持原有 before-quit/will-quit 的包裹结构不动，只换主体调用。）

第 1231-1234 行 IPC 替换为：
```js
ipcMain.handle('checkba:backend-restart', async () => {
  if (!services) {
    services = createServices()
    await services.allocatePorts()
  }
  return services.restart('backend')
})
```

- [ ] **Step 3: 删除旧文件**

```bash
git rm desktop/main/backend.js
```

- [ ] **Step 4: 回归验证（dev 态）**

前置：`backend/target/backend-0.0.1-SNAPSHOT.jar` 已存在（没有则先 `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests package`，注意 PostgreSQL dev 态要在跑，或临时 `SPRING_PROFILES_ACTIVE=desktop`）。

```bash
cd desktop && SPRING_PROFILES_ACTIVE=desktop npm run dev
```
预期：应用窗口打开，`curl http://127.0.0.1:9696/api/admin/wizard` 返回 JSON。退出应用后 `lsof -i :9696` 无残留 java 进程。

- [ ] **Step 5: 跑单元测试防回归**

```bash
cd desktop && npm test
```
预期：PASS

- [ ] **Step 6: 提交**

```bash
git add desktop/main/services/backend-service.js desktop/main/main.js
git rm --cached desktop/main/backend.js 2>/dev/null; git add -A desktop/main
git commit -m "refactor(desktop): Java 后端迁入 ServiceManager——行为不变，为多服务打包铺路 / migrate backend into service manager"
```

---

### Task 3: pptx-service 支持 PPTX_DATA_DIR（打包态可写数据目录）

**Files:**
- Modify: `pptx-service/backend/app.py:58-70`（create_app 内路径段）
- Test: `pptx-service/backend/tests/test_data_dir.py`

**Interfaces:**
- Produces: 环境变量 `PPTX_DATA_DIR`——设置后 SQLite 落 `$PPTX_DATA_DIR/instance/database.db`、上传落 `$PPTX_DATA_DIR/uploads`；未设置时行为与现状完全一致（backend 源目录下 instance/、项目根 uploads/）。Alembic 迁移经 `migrations/env.py` 的 `create_app()` 自动继承该行为（env.py:29 `get_url()` 调 create_app）。

- [ ] **Step 1: 写失败的测试**

`pptx-service/backend/tests/test_data_dir.py`：

```python
"""PPTX_DATA_DIR: packaged desktop mode writes DB/uploads outside read-only resources"""
import os
import importlib


def test_data_dir_env_redirects_db_and_uploads(tmp_path, monkeypatch):
    monkeypatch.setenv('PPTX_DATA_DIR', str(tmp_path))
    import app as app_module
    application = app_module.create_app()
    expected_db = os.path.join(str(tmp_path), 'instance', 'database.db')
    assert application.config['SQLALCHEMY_DATABASE_URI'] == f'sqlite:///{expected_db}'
    assert application.config['UPLOAD_FOLDER'] == os.path.join(str(tmp_path), 'uploads')
    assert os.path.isdir(os.path.join(str(tmp_path), 'instance'))
    assert os.path.isdir(os.path.join(str(tmp_path), 'uploads'))


def test_without_env_keeps_legacy_paths(monkeypatch):
    monkeypatch.delenv('PPTX_DATA_DIR', raising=False)
    import app as app_module
    application = app_module.create_app()
    backend_dir = os.path.dirname(os.path.abspath(app_module.__file__))
    assert application.config['SQLALCHEMY_DATABASE_URI'].endswith(
        os.path.join(backend_dir, 'instance', 'database.db'))
    assert application.config['UPLOAD_FOLDER'] == os.path.join(
        os.path.dirname(backend_dir), 'uploads')
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd pptx-service && uv run pytest backend/tests/test_data_dir.py -v
```
预期：`test_data_dir_env_redirects_db_and_uploads` FAIL（URI 仍指向源目录）；第二个 PASS。

- [ ] **Step 3: 改 app.py**

`app.py:58-70` 原文：

```python
    # Override with environment-specific paths (use absolute path)
    backend_dir = os.path.dirname(os.path.abspath(__file__))
    instance_dir = os.path.join(backend_dir, 'instance')
    os.makedirs(instance_dir, exist_ok=True)
    
    db_path = os.path.join(instance_dir, 'database.db')
    app.config['SQLALCHEMY_DATABASE_URI'] = f'sqlite:///{db_path}'
    
    # Ensure upload folder exists
    project_root = os.path.dirname(backend_dir)
    upload_folder = os.path.join(project_root, 'uploads')
    os.makedirs(upload_folder, exist_ok=True)
    app.config['UPLOAD_FOLDER'] = upload_folder
```

替换为：

```python
    # Override with environment-specific paths (use absolute path).
    # PPTX_DATA_DIR: desktop 打包态注入（resources 目录只读，数据必须外置）
    backend_dir = os.path.dirname(os.path.abspath(__file__))
    data_dir = os.getenv('PPTX_DATA_DIR')
    instance_dir = os.path.join(data_dir or backend_dir, 'instance')
    os.makedirs(instance_dir, exist_ok=True)
    
    db_path = os.path.join(instance_dir, 'database.db')
    app.config['SQLALCHEMY_DATABASE_URI'] = f'sqlite:///{db_path}'
    
    # Ensure upload folder exists
    project_root = os.path.dirname(backend_dir)
    upload_folder = os.path.join(data_dir or project_root, 'uploads')
    os.makedirs(upload_folder, exist_ok=True)
    app.config['UPLOAD_FOLDER'] = upload_folder
```

- [ ] **Step 4: 跑测试确认通过 + 既有测试不回归**

```bash
cd pptx-service && uv run pytest backend/tests/ -v
```
预期：全部 PASS（新增 2 项 + 既有项）。

- [ ] **Step 5: 提交**

```bash
git add pptx-service/backend/app.py pptx-service/backend/tests/test_data_dir.py
git commit -m "feat(pptx-service): PPTX_DATA_DIR 环境变量外置数据目录——桌面打包态只读资源适配 / relocatable data dir for desktop bundle"
```

---

### Task 4: requirements.lock + prepare-python-service.js（运行时烙制脚本）

**Files:**
- Create: `pptx-service/requirements.lock`（uv 导出，入库）
- Create: `desktop/scripts/prepare-python-service.js`

**Interfaces:**
- Consumes: python-build-standalone release 资产命名规则（`cpython-3.11.12+20250409-<triple>-install_only.tar.gz`）。
- Produces: `desktop/bundled/<os>-<arch>/python/`（共享运行时，unix 布局 `python/bin/python3.11`，win 布局 `python/python.exe`）与 `desktop/bundled/<os>-<arch>/pysvc/pptx-service/{app,lib}`（app=服务源码，lib=pip --target 的 site-packages）。CLI：
  `node desktop/scripts/prepare-python-service.js --service pptx-service --src pptx-service/backend --requirements pptx-service/requirements.lock --out desktop/bundled/<os>-<arch> [--pip-platform macosx_11_0_x86_64]`

- [ ] **Step 1: 生成并入库 requirements.lock**

```bash
cd pptx-service && uv export --no-dev --no-hashes --no-emit-project -o requirements.lock
git add pptx-service/requirements.lock
```
预期：文件包含 flask、flask-sqlalchemy、alembic、python-pptx、google-genai/openai 等全量 pin。
（--no-hashes：跨平台 `pip --platform` 安装时 hash 会因平台 wheel 不同而失配；锁版本号已足够复现。）

- [ ] **Step 2: 写 prepare-python-service.js**

`desktop/scripts/prepare-python-service.js`：

```js
#!/usr/bin/env node
/*
 * 烙制"共享 Python 运行时 + 单服务 site-packages + 服务源码"进 desktop/bundled/，
 * 供 electron-builder extraResources 打包（对标 prepare-backend.js 的 jar+JRE 链路）。
 *
 * 用法：
 *   node scripts/prepare-python-service.js \
 *     --service pptx-service \
 *     --src ../pptx-service/backend \
 *     --requirements ../pptx-service/requirements.lock \
 *     --out bundled/mac-arm64 \
 *     [--pip-platform macosx_11_0_x86_64]   # 交叉安装（arm64 runner 烙 Intel 包）
 *
 * 共享运行时：同一 out 目录下多次调用只下载/解压一次 python/。
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const PBS_RELEASE = '20250409'
const PY_VERSION = '3.11.12'

function pbsTriple() {
  if (process.platform === 'darwin') {
    return process.env.PBS_ARCH === 'x64' || process.argv.includes('--pbs-x64')
      ? 'x86_64-apple-darwin'
      : (process.arch === 'arm64' ? 'aarch64-apple-darwin' : 'x86_64-apple-darwin')
  }
  if (process.platform === 'win32') return 'x86_64-pc-windows-msvc'
  return 'x86_64-unknown-linux-gnu'
}

function parseArgs() {
  const out = {}
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const key = argv[i].slice(2)
      if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) {
        out[key] = argv[++i]
      } else {
        out[key] = true
      }
    }
  }
  for (const k of ['service', 'src', 'requirements', 'out']) {
    if (!out[k]) {
      console.error(`missing --${k}`)
      process.exit(1)
    }
  }
  return out
}

function pythonBin(pyRoot) {
  return process.platform === 'win32'
    ? path.join(pyRoot, 'python.exe')
    : path.join(pyRoot, 'bin', 'python3.11')
}

function ensurePython(outDir) {
  const pyRoot = path.join(outDir, 'python')
  if (fs.existsSync(pythonBin(pyRoot))) {
    console.log(`python runtime already present: ${pyRoot}`)
    return pyRoot
  }
  const triple = pbsTriple()
  const name = `cpython-${PY_VERSION}+${PBS_RELEASE}-${triple}-install_only.tar.gz`
  const url = process.env.PBS_BASE_URL
    ? `${process.env.PBS_BASE_URL}/${name}`
    : `https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_RELEASE}/${name}`
  const tarball = path.join(outDir, name)
  fs.mkdirSync(outDir, { recursive: true })
  console.log(`downloading ${url}`)
  execFileSync('curl', ['-fSL', '--retry', '3', '-o', tarball, url], { stdio: 'inherit' })
  // install_only 包解压即得顶层 python/ 目录
  execFileSync('tar', ['-xzf', tarball, '-C', outDir], { stdio: 'inherit' })
  fs.rmSync(tarball)
  if (!fs.existsSync(pythonBin(pyRoot))) {
    console.error(`unexpected layout after extract: ${pyRoot}`)
    process.exit(1)
  }
  return pyRoot
}

function installDeps(pyRoot, requirements, libDir, pipPlatform) {
  fs.rmSync(libDir, { recursive: true, force: true })
  fs.mkdirSync(libDir, { recursive: true })
  const args = ['-m', 'pip', 'install', '--no-compile', '--target', libDir, '-r', requirements]
  if (pipPlatform) {
    // 交叉安装：只认二进制 wheel，逐平台 tag 放宽（universal2 兜底）
    args.push('--only-binary=:all:', '--python-version', '311', '--implementation', 'cp')
    for (const tag of [pipPlatform, 'macosx_10_9_x86_64', 'macosx_11_0_universal2']) {
      args.push('--platform', tag)
    }
  }
  execFileSync(pythonBin(pyRoot), args, { stdio: 'inherit' })
}

function copyAppSource(srcDir, appDir) {
  fs.rmSync(appDir, { recursive: true, force: true })
  const EXCLUDES = new Set(['tests', 'instance', '__pycache__', '.pytest_cache', 'Dockerfile', 'run.bat', 'run.sh'])
  fs.cpSync(srcDir, appDir, {
    recursive: true,
    filter: (src) => !EXCLUDES.has(path.basename(src))
  })
}

function prune(libDir) {
  // 体积裁剪：字节码缓存与测试目录（保守起见不动 dist-info——pip/importlib.metadata 需要）
  const stack = [libDir]
  while (stack.length) {
    const dir = stack.pop()
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name)
      if (!entry.isDirectory()) continue
      if (entry.name === '__pycache__') fs.rmSync(p, { recursive: true, force: true })
      else stack.push(p)
    }
  }
}

function main() {
  const args = parseArgs()
  const outDir = path.resolve(args.out)
  const pyRoot = ensurePython(outDir)
  const svcDir = path.join(outDir, 'pysvc', args.service)
  const libDir = path.join(svcDir, 'lib')
  const appDir = path.join(svcDir, 'app')
  installDeps(pyRoot, path.resolve(args.requirements), libDir, args['pip-platform'])
  copyAppSource(path.resolve(args.src), appDir)
  prune(libDir)
  console.log(`bundled ${args.service}:`)
  console.log(`  runtime: ${pyRoot}`)
  console.log(`  lib:     ${libDir}`)
  console.log(`  app:     ${appDir}`)
}

main()
```

- [ ] **Step 3: 本机（mac-arm64）验证烙制 + 真实拉起**

```bash
cd desktop
node scripts/prepare-python-service.js \
  --service pptx-service \
  --src ../pptx-service/backend \
  --requirements ../pptx-service/requirements.lock \
  --out bundled/mac-arm64
# 用烙好的运行时跑迁移 + 起服务（模拟打包态 spawn）
export PPTX_HOME="$TMPDIR/pptx-smoke"
mkdir -p "$PPTX_HOME"
cd bundled/mac-arm64/pysvc/pptx-service/app
PYTHONPATH=../lib PPTX_DATA_DIR="$PPTX_HOME" ../../../python/bin/python3.11 -m alembic -c alembic.ini upgrade head
PYTHONPATH=../lib PPTX_DATA_DIR="$PPTX_HOME" PORT=5099 FLASK_ENV=production ../../../python/bin/python3.11 app.py &
sleep 3
curl -sf http://127.0.0.1:5099/health
kill %1
```
预期：alembic 输出迁移日志；curl 返回 `{"status": "ok", ...}`；`$PPTX_HOME/instance/database.db` 存在。

- [ ] **Step 4: 交叉烙制验证（mac-arm64 机器上烙 Intel 包，Phase 1 风险点前置）**

```bash
cd desktop
PBS_ARCH=x64 node scripts/prepare-python-service.js \
  --service pptx-service \
  --src ../pptx-service/backend \
  --requirements ../pptx-service/requirements.lock \
  --out bundled/mac-x64 \
  --pbs-x64 \
  --pip-platform macosx_11_0_x86_64
file bundled/mac-x64/python/bin/python3.11 | grep x86_64
ls bundled/mac-x64/pysvc/pptx-service/lib | head
```
预期：python 二进制是 x86_64；lib 装满。若某依赖无 x86_64 wheel 导致 pip 失败：记下依赖名，兜底方案是 CI 矩阵给 mac x64 单开 `macos-13`（Intel runner）——把结论写进 PR 描述再继续。

- [ ] **Step 5: 提交**

```bash
git add pptx-service/requirements.lock desktop/scripts/prepare-python-service.js
git commit -m "feat(desktop): Python 服务烙制脚本——python-build-standalone 运行时 + pip --target 交叉安装 / python service bundling script"
```

---

### Task 5: pptx 描述符 + main.js 注册 + extraResources

**Files:**
- Create: `desktop/main/services/pptx-service.js`
- Modify: `desktop/main/main.js`（`createServices()` 内加一行注册）
- Modify: `desktop/package.json`（extraResources 增两项）

**Interfaces:**
- Consumes: Task 1 manager 契约、Task 2 的 `createServices()`、Task 4 的 bundled 布局（`resources/python`、`resources/pysvc/pptx-service/{app,lib}`）。
- Produces: 打包态 resources 下 python 运行时与 pptx 服务就位；后端 env `EXTERNAL_PPTX_SERVICE_BASE_URL` 已由 Task 2 的 `spawnEnv()` 注入（依赖 `ctx.ports['pptx-service']` 存在——注册顺序保证：pptx 在 backend 之前注册即可让 allocatePorts 先填它？**不需要**：allocatePorts 对全部描述符先行分配完毕后才 startEager，注册顺序只影响启动次序）。

- [ ] **Step 1: 写 pptx 描述符**

`desktop/main/services/pptx-service.js`：

```js
const path = require('path')
const fs = require('fs')
const { spawnSync } = require('child_process')
const { findFreePort } = require('./service-manager')

function pyBin(ctx) {
  return process.platform === 'win32'
    ? path.join(ctx.resourcesPath, 'python', 'python.exe')
    : path.join(ctx.resourcesPath, 'python', 'bin', 'python3.11')
}

function appDir(ctx) {
  return path.join(ctx.resourcesPath, 'pysvc', 'pptx-service', 'app')
}

function libDir(ctx) {
  return path.join(ctx.resourcesPath, 'pysvc', 'pptx-service', 'lib')
}

function dataDir(ctx) {
  return path.join(ctx.dataDir, 'pptx')
}

function spawnEnv(ctx) {
  return {
    ...process.env,
    PYTHONPATH: libDir(ctx),
    PPTX_DATA_DIR: dataDir(ctx),
    PORT: String(ctx.ports['pptx-service']),
    FLASK_ENV: 'production'
  }
}

function createPptxDescriptor() {
  return {
    name: 'pptx-service',
    eager: true, // Phase 1 有意 eager（轻量 Flask；lazy 机制随 Phase 2 mineru 落地）
    logName: 'pptx-service',
    // dev 态不 spawn（commands 返回空）：沿用现状——docker compose 起在 5001，复用检测直接 reuse
    port: async (ctx) => {
      if (process.env.CHECKBA_PPTX_PORT) return Number(process.env.CHECKBA_PPTX_PORT)
      // dev 态固定 5001（对齐 application.yml 默认值与 docker 映射）；打包态动态挑空闲端口
      return ctx.packaged ? findFreePort() : 5001
    },
    startTimeoutMs: () => 30000,
    prepare: async (ctx) => {
      if (!ctx.packaged) return
      fs.mkdirSync(dataDir(ctx), { recursive: true })
      // SQLite schema 迁移（alembic.ini/migrations 随 app 源码打包；env.py 经 create_app
      // 读 PPTX_DATA_DIR，迁移与运行落同一个库）
      const r = spawnSync(pyBin(ctx), ['-m', 'alembic', '-c', path.join(appDir(ctx), 'alembic.ini'), 'upgrade', 'head'], {
        cwd: appDir(ctx),
        env: spawnEnv(ctx),
        stdio: 'pipe',
        encoding: 'utf8'
      })
      if (r.status !== 0) {
        throw new Error(`pptx alembic migration failed: ${(r.stderr || '').slice(-2000)}`)
      }
    },
    commands: (ctx) => {
      if (!ctx.packaged) return [] // dev 态只做复用检测，不自行 spawn
      return [{
        cmd: pyBin(ctx),
        args: [path.join(appDir(ctx), 'app.py')],
        env: spawnEnv(ctx),
        cwd: dataDir(ctx)
      }]
    }
  }
}

module.exports = { createPptxDescriptor }
```

**注意**：dev 态 `commands()` 返回空数组 → manager 轮试为空直接 throw。这是预期：dev 态若 5001 没有 docker 服务在跑，pptx 功能本来就不可用；startEager 已 catch 单服务错误不阻塞主窗口。若想静音，dev 态错误信息会落 console，不影响使用。

- [ ] **Step 2: main.js 注册**

`createServices()`（Task 2 引入）内、`mgr.register(createBackendDescriptor())` 之后加：

```js
  const { createPptxDescriptor } = require('./services/pptx-service')
  mgr.register(createPptxDescriptor())
```

**启动顺序修正**：backend 的 `spawnEnv` 读 `ctx.ports['pptx-service']`，而 `startEager()` 按注册序启动——backend 先启动时 ports 表已在 `allocatePorts()` 全量填好，顺序无碍。**但 pptx 必须晚于 backend 拉起吗？不必**，两者无启动时依赖（Java 侧只在工具调用时访问 pptx URL）。

- [ ] **Step 3: extraResources 增项**

`desktop/package.json` 的 `build.extraResources` 数组追加：

```json
{
  "from": "bundled/${os}-${arch}/python",
  "to": "python"
},
{
  "from": "bundled/${os}-${arch}/pysvc",
  "to": "pysvc"
}
```

- [ ] **Step 4: 单测回归 + 本机端到端（可选但推荐）**

```bash
cd desktop && npm test
```
预期：PASS。

本机完整验证（需 Task 4 已烙 bundled/mac-arm64 且本机能出 dmg，可跳过签名：`CSC_IDENTITY_AUTO_DISCOVERY=false npx electron-builder --mac dmg --publish never`），装后启动，检查 `~/.aiworkdeck/logs/pptx-service.log` 出现 Flask 启动横幅、`~/.aiworkdeck/pptx/instance/database.db` 存在。

- [ ] **Step 5: 提交**

```bash
git add desktop/main/services/pptx-service.js desktop/main/main.js desktop/package.json
git commit -m "feat(desktop): pptx-service 进包——捆绑 Python 运行时拉起 + 动态端口注入后端 / bundle pptx-service into installer"
```

---

### Task 6: CI 集成（烙制 + 签名 + 冒烟）

**Files:**
- Modify: `.github/workflows/desktop-build.yml`（"Bundle backend" 步骤后、签名步骤前插入；两处 smoke test 后各加 pptx 冒烟）
- Modify: `desktop/scripts/sign-mac-natives.sh:40-44`（JRE 签名循环泛化为 jre+python 两目录）

**Interfaces:**
- Consumes: Task 4 脚本 CLI、Task 5 的 bundled 布局。

- [ ] **Step 1: sign-mac-natives.sh 覆盖 python 目录**

原文（第 40-44 行）：

```bash
# --- 1) JRE ---------------------------------------------------------------
find "$BUNDLE_DIR/jre" -type f | while read -r f; do
  file -b "$f" | grep -q 'Mach-O' || continue
  needs_sign "$f" || continue
  sign_file "$f"
done
```

替换为：

```bash
# --- 1) 捆绑的运行时（jlink JRE + python-build-standalone） -----------------
for runtime_dir in "$BUNDLE_DIR/jre" "$BUNDLE_DIR/python" "$BUNDLE_DIR/pysvc"; do
  [ -d "$runtime_dir" ] || continue
  find "$runtime_dir" -type f | while read -r f; do
    file -b "$f" | grep -q 'Mach-O' || continue
    needs_sign "$f" || continue
    sign_file "$f"
  done
done
```
（pysvc 也扫：pip 装的包内可能有 .so/.dylib，如 sqlalchemy C 扩展。）

- [ ] **Step 2: desktop-build.yml 加烙制步骤**

在 `Bundle backend (macOS arm64 + x64)` 步骤之后插入：

```yaml
      - name: Bundle pptx-service (macOS arm64 + x64)
        if: runner.os == 'macOS'
        run: |
          node desktop/scripts/prepare-python-service.js \
            --service pptx-service \
            --src pptx-service/backend \
            --requirements pptx-service/requirements.lock \
            --out desktop/bundled/mac-arm64
          # Intel 包在 arm64 runner 上交叉烙制（PBS x64 运行时 + pip --platform 只取 x86_64 wheel）
          PBS_ARCH=x64 node desktop/scripts/prepare-python-service.js \
            --service pptx-service \
            --src pptx-service/backend \
            --requirements pptx-service/requirements.lock \
            --out desktop/bundled/mac-x64 \
            --pbs-x64 \
            --pip-platform macosx_11_0_x86_64
```

在 `Bundle backend (Windows x64)` 步骤之后插入：

```yaml
      - name: Bundle pptx-service (Windows x64)
        if: runner.os == 'Windows'
        shell: bash
        run: |
          node desktop/scripts/prepare-python-service.js \
            --service pptx-service \
            --src pptx-service/backend \
            --requirements pptx-service/requirements.lock \
            --out desktop/bundled/win-x64
```

- [ ] **Step 3: 加 pptx 冒烟测试**

在 `Smoke test bundled backend (macOS)` 之后插入：

```yaml
      - name: Smoke test bundled pptx-service (macOS)
        if: runner.os == 'macOS'
        run: |
          # 用烙好的运行时按打包态同款方式拉起：先迁移，后起服务，验 /health
          export PPTX_DATA_DIR="$RUNNER_TEMP/pptx-data"
          mkdir -p "$PPTX_DATA_DIR"
          APP=desktop/bundled/mac-arm64/pysvc/pptx-service/app
          PY=desktop/bundled/mac-arm64/python/bin/python3.11
          PYTHONPATH=desktop/bundled/mac-arm64/pysvc/pptx-service/lib \
            "$PY" -m alembic -c "$APP/alembic.ini" upgrade head
          PYTHONPATH=desktop/bundled/mac-arm64/pysvc/pptx-service/lib PORT=5099 FLASK_ENV=production \
            "$PY" "$APP/app.py" > "$RUNNER_TEMP/pptx-smoke.log" 2>&1 &
          PPTX_PID=$!
          for i in $(seq 1 30); do
            sleep 2
            if curl -sf http://127.0.0.1:5099/health; then
              echo; echo "pptx-service up after ~$((i*2))s"
              kill $PPTX_PID
              exit 0
            fi
          done
          echo "pptx-service failed to start within 60s"
          tail -80 "$RUNNER_TEMP/pptx-smoke.log"
          kill $PPTX_PID 2>/dev/null || true
          exit 1
```
（alembic 需要 cwd 无关化：`-c` 用绝对路径 + env.py 自行 sys.path 注入 app 目录——与本地验证 Step 3/Task 4 的差异是这里用绝对路径而非 cd，两种都必须可用；若 alembic 的 script_location 相对解析失败，在该步骤加 `cd "$APP"` 即可，本地验证已覆盖此形态。）

在 `Smoke test bundled backend (Windows)` 之后插入同款（Windows 路径）：

```yaml
      - name: Smoke test bundled pptx-service (Windows)
        if: runner.os == 'Windows'
        shell: bash
        run: |
          export PPTX_DATA_DIR="$RUNNER_TEMP/pptx-data"
          mkdir -p "$PPTX_DATA_DIR"
          APP=desktop/bundled/win-x64/pysvc/pptx-service/app
          PY=desktop/bundled/win-x64/python/python.exe
          PYTHONPATH=desktop/bundled/win-x64/pysvc/pptx-service/lib \
            "$PY" -m alembic -c "$APP/alembic.ini" upgrade head
          PYTHONPATH=desktop/bundled/win-x64/pysvc/pptx-service/lib PORT=5099 FLASK_ENV=production \
            "$PY" "$APP/app.py" > "$RUNNER_TEMP/pptx-smoke.log" 2>&1 &
          PPTX_PID=$!
          for i in $(seq 1 30); do
            sleep 2
            if curl -sf http://127.0.0.1:5099/health; then
              echo; echo "pptx-service up after ~$((i*2))s"
              kill $PPTX_PID
              exit 0
            fi
          done
          echo "pptx-service failed to start within 60s"
          tail -80 "$RUNNER_TEMP/pptx-smoke.log"
          kill $PPTX_PID 2>/dev/null || true
          exit 1
```

- [ ] **Step 4: 加 desktop 单元测试步骤**

在 `Install desktop dependencies` 之后插入：

```yaml
      - name: Desktop unit tests
        working-directory: desktop
        run: npm test
```

- [ ] **Step 5: 提交并推 PR 触发 CI**

```bash
git add .github/workflows/desktop-build.yml desktop/scripts/sign-mac-natives.sh
git commit -m "ci(desktop): pptx-service 三平台烙制+签名+冒烟——安装包含 Python 运行时 / bundle & smoke-test pptx-service in CI"
git push -u origin <branch>
gh pr create --fill
```
预期：desktop-build.yml 两个矩阵 job 全绿（含新旧四个冒烟步骤 + 单测 + 公证）。dmg 体积增量约 +150~250MB（python 3.11 约 45MB 压缩 + 依赖树），在 PR 描述里记录实际值。

---

### Task 7: 文档对齐 + 收尾

**Files:**
- Modify: `docs/DESKTOP_LOCAL_BUNDLE_PLAN.md`（§2.1 表格 pptx-service 行：lazy → eager，附一句原因；§3 Phase 1 勾掉）
- Modify: `desktop/README.md`（打包步骤补 python 烙制命令；捆绑清单补 python/pysvc）

- [ ] **Step 1: 设计文档修订**

`docs/DESKTOP_LOCAL_BUNDLE_PLAN.md` §2.1 表格中 pptx-service 行：

```
| pptx-service | 捆绑 Python | eager（轻量 Flask，触发方在 Java agent 工具循环内、渲染层无 ensure 拦截点；lazy 机制随 Phase 2 mineru 落地） | Flask + SQLite，秒级启动 |
```

- [ ] **Step 2: desktop/README.md 补打包说明**

在既有打包步骤（prepare-backend）后追加：

```markdown
### 3.5 烙制 pptx-service（Python 运行时 + 依赖 + 源码）

    node scripts/prepare-python-service.js \
      --service pptx-service \
      --src ../pptx-service/backend \
      --requirements ../pptx-service/requirements.lock \
      --out bundled/mac-arm64

产物：`bundled/<os>-<arch>/python/`（共享 3.11 运行时）与 `bundled/<os>-<arch>/pysvc/pptx-service/{app,lib}`。
打包态由 ServiceManager 自动拉起（动态端口，经 `EXTERNAL_PPTX_SERVICE_BASE_URL` 注入后端）；
数据落 `~/.aiworkdeck/pptx/`，日志落 `~/.aiworkdeck/logs/pptx-service.log`。
```

- [ ] **Step 3: 提交**

```bash
git add -f docs/DESKTOP_LOCAL_BUNDLE_PLAN.md
git add desktop/README.md
git commit -m "docs(desktop): Phase 1 落地对齐——pptx eager 决策与打包步骤 / phase 1 doc alignment"
```

---

## Self-Review 结论（已执行）

1. **Spec 覆盖**：设计文档 Phase 1 五项——ServiceManager 重构（Task 1/2）、prepare-python-service.js + requirements.lock（Task 4）、pptx 进 extraResources + lazy 启动 + 动态端口（Task 5，lazy→eager 为已声明偏离）、CI 烙制+冒烟（Task 6）、mac x64 交叉验证前置（Task 4 Step 4）。✔
2. **占位符扫描**：无 TBD/TODO；所有代码块完整可粘贴。✔
3. **类型/签名一致性**：`createServiceManager(ctx)`、`ctx.ports['pptx-service']`、`EXTERNAL_PPTX_SERVICE_BASE_URL`、bundled 布局 `python/` + `pysvc/<svc>/{app,lib}` 在 Task 2/4/5/6 间交叉引用一致。✔

已知风险提醒（执行时注意）：
- pip 交叉安装若因某依赖缺 x86_64 wheel 失败，兜底是 CI 矩阵为 mac x64 单开 `macos-13` Intel runner（Task 4 Step 4 有明确指引）。
- alembic `script_location = migrations` 为相对路径，CI 冒烟用 `-c` 绝对路径若解析失败则 `cd "$APP"` 再跑（Task 6 Step 3 已注明）。
- Windows 上 `kill`/后台进程在 bash shell 下行为与 mac 一致（GitHub runner 的 git-bash 支持），已沿用既有 backend 冒烟的同款写法。
