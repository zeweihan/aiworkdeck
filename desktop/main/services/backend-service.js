const path = require('path')
const fs = require('fs')
const net = require('net')
const http = require('http')
const { spawn, execFileSync } = require('child_process')
const { isPortOpen } = require('./service-manager')

// 打包态后端端口链：默认 5269，被占则依次降级（5269 是 IANA 注册的 XMPP
// 服务器互联口，桌面软件几乎不用；5369/5169 未注册）。dev 态保持 9696，
// 与 restart-backend.sh / e2e / CI 的既有工作流一致。
const DESKTOP_PORT_CHAIN = [5269, 5369, 5169]

// 端口上监听的是不是我们自己的后端：探 GET /api/admin/wizard，
// 该端点免鉴权、回环放行，返回体带 "initialized" 特征字段。
// 用于防止把陌生进程（恰好占了链上端口）误当后端复用。
// 返回 { ours, build }：build 是后端启动时经 AWD_BACKEND_BUILD 注入的 jar 指纹
// （旧后端/非桌面壳拉起的后端为空串）。
function probeBackend(port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/api/admin/wizard', timeout: timeoutMs },
      (res) => {
        let body = ''
        res.on('data', (c) => { if (body.length < 4096) body += c })
        res.on('end', () => {
          const ours = res.statusCode === 200 && body.includes('"initialized"')
          let build = ''
          try { build = String(JSON.parse(body).build || '') } catch (e) { /* 旧后端无此字段 */ }
          resolve({ ours, build })
        })
      }
    )
    req.on('timeout', () => { req.destroy(); resolve({ ours: false, build: '' }) })
    req.on('error', () => resolve({ ours: false, build: '' }))
  })
}

/**
 * 当前会启动的那份 app.jar 的指纹（size-mtime）。spawn 时经 AWD_BACKEND_BUILD 注入，
 * wizard 端点原样回显；复用前对比即可识破「监听着端口的是更新前残留的陈旧后端」——
 * 2026-08-24 维护者真机就复用了 8-21 的旧后端，evidence-links 一路 404 还毫无提示
 * （dev-board#139 根因之一）。指纹取文件属性而不取版本号，是因为 overlay 补丁换
 * app.jar 不改壳版本号，AWD_APP_VERSION 分辨不出 0.24.0 壳下的 0.24.1/0.24.2 后端。
 */
function backendBuildId(ctx) {
  try {
    const st = fs.statSync(jarPath(ctx))
    return `${st.size}-${Math.round(st.mtimeMs)}`
  } catch (e) {
    return ''
  }
}

/**
 * 复用裁决（纯函数，单测覆盖）：
 * - 陌生进程 → 'skip'（走下一个端口/重新分配）
 * - 自家后端且指纹一致 → 'reuse'（多开实例/上次残留的同版本后端，正常复用）
 * - 自家后端但指纹不一致（陈旧版本）→ 'replace'（礼貌终止后原地重启；
 *   终止失败时调用方退回 'reuse'——陈旧但能用，好过 H2 文件锁把新后端卡死）
 * dev 态不做指纹校验：restart-backend.sh 等外部工作流拉起的后端没有注入指纹，
 * 版本闸只针对打包态「更新后复用残留旧后端」的形态。
 */
function decideReuse(probe, { packaged, expectedBuild }) {
  if (!probe.ours) return 'skip'
  if (!packaged) return 'reuse'
  if (!expectedBuild || probe.build === expectedBuild) return 'reuse'
  return 'replace'
}

/** 找出监听该端口的 pid（仅回环）。失败返回 null。 */
function pidListeningOn(port) {
  try {
    if (process.platform === 'win32') {
      const out = execFileSync('netstat', ['-ano', '-p', 'tcp'], { encoding: 'utf8' })
      for (const line of out.split(/\r?\n/)) {
        const m = line.trim().match(/^TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)$/i)
        if (m && Number(m[1]) === port) return Number(m[2])
      }
      return null
    }
    const out = execFileSync('lsof', ['-ti', `tcp:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' })
    const pid = Number(out.split(/\s+/).filter(Boolean)[0])
    return Number.isFinite(pid) ? pid : null
  } catch (e) {
    return null
  }
}

/**
 * 终止端口上的陈旧后端并等它放开端口。只在 probeBackend 验明「确是我们的后端、
 * 但版本不是当前要启动的」之后调用——按端口找 pid 定点终止，绝不按进程名扫射
 * （多会话并行时按名杀会误伤别人的后端）。返回是否已释放端口。
 */
async function terminateStaleBackend(port, waitMs = 8000) {
  const pid = pidListeningOn(port)
  if (!pid) return !(await isPortOpen(port))
  try {
    if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(pid), '/T', '/F'])
    else process.kill(pid, 'SIGTERM')
  } catch (e) {
    return false
  }
  const deadline = Date.now() + waitMs
  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    if (!(await isPortOpen(port))) return true
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 250))
  }
  // SIGTERM 没生效：升级 SIGKILL 再等一小段（win32 上面已经是 /F 强杀）
  if (process.platform !== 'win32') {
    try { process.kill(pid, 'SIGKILL') } catch (e) { return false }
    const hardDeadline = Date.now() + 3000
    while (Date.now() < hardDeadline) {
      // eslint-disable-next-line no-await-in-loop
      if (!(await isPortOpen(port))) return true
      // eslint-disable-next-line no-await-in-loop
      await new Promise((r) => setTimeout(r, 250))
    }
  }
  return false
}

// 能否真实绑定该端口（listen 后立刻释放；比 isPortOpen 的 connect 探测可靠——
// 有些占用是 bind 而不 accept）。释放到 spawn 之间存在被抢窗口，与 findFreePort 同级，可接受。
function canBind(port) {
  return new Promise((resolve) => {
    const s = net.createServer()
    s.once('error', () => resolve(false))
    s.listen(port, '127.0.0.1', () => s.close(() => resolve(true)))
  })
}

async function allocateBackendPort(ctx) {
  // 显式覆盖优先（e2e/脚本用）
  if (process.env.CHECKBA_BACKEND_PORT) return Number(process.env.CHECKBA_BACKEND_PORT)
  // dev 态维持 9696：复用 restart-backend.sh 起的后端，脚本/CI 不受影响
  if (!ctx.packaged) return 9696
  const expectedBuild = backendBuildId(ctx)
  for (const cand of DESKTOP_PORT_CHAIN) {
    // eslint-disable-next-line no-await-in-loop
    if (await isPortOpen(cand)) {
      // 有人在听：同版本自家后端复用，陌生进程跳过，陈旧自家后端定点终止后原地重启
      // eslint-disable-next-line no-await-in-loop
      const verdict = decideReuse(await probeBackend(cand), { packaged: true, expectedBuild })
      if (verdict === 'reuse') return cand
      if (verdict === 'replace') {
        // eslint-disable-next-line no-await-in-loop
        const freed = await terminateStaleBackend(cand)
        // eslint-disable-next-line no-await-in-loop
        if (freed && await canBind(cand)) return cand
        // 终止不了（权限/僵尸）：退而复用陈旧后端——它还握着 H2 文件锁，
        // 硬起新后端只会双双卡死；陈旧但能用，好过打不开
        console.warn(`[backend] 端口 ${cand} 上的陈旧后端无法终止，退而复用（功能可能落后于本体版本）`)
        return cand
      }
      continue
    }
    // eslint-disable-next-line no-await-in-loop
    if (await canBind(cand)) return cand
  }
  // 链上全被占：退随机空闲端口，保证应用仍能启动（渲染层经注入拿到实际端口）
  const { findFreePort } = require('./service-manager')
  return findFreePort()
}

const BACKEND_MAIN_CLASS = 'com.checkba.CheckbaApplication'

/**
 * 后端布局解析（增量更新设计 §4.1）：
 * - 打包态新布局：resources/backend/{app.jar, lib/}——业务代码与依赖分离，
 *   补丁只换 app.jar（overlay 覆盖优先，见 overlay.js）；lib/ 只随大版本走。
 * - 打包态旧布局（backend.jar fat jar）：兼容保留，-jar 直启。
 * - dev 态：target 下的 fat jar（不走 overlay）。
 */
function backendLayout(ctx) {
  if (!ctx.packaged) {
    return { kind: 'fat', jar: path.join(ctx.projectRoot, 'backend', 'target', 'backend-0.0.1-SNAPSHOT.jar') }
  }
  const base = path.join(ctx.resourcesPath, 'backend')
  const builtinApp = path.join(base, 'app.jar')
  if (fs.existsSync(builtinApp)) {
    // overlay seam：补丁的 app.jar 覆盖内置（激活/回滚由 update-service 管理）
    let appJar = builtinApp
    try {
      const overlay = require('./overlay')
      const dir = overlay.componentDir(ctx, 'backend-app')
      if (dir && fs.existsSync(path.join(dir, 'app.jar'))) appJar = path.join(dir, 'app.jar')
    } catch (e) { /* overlay 损坏时静默回内置 */ }
    return { kind: 'split', appJar, libDir: path.join(base, 'lib') }
  }
  return { kind: 'fat', jar: path.join(base, 'backend.jar') }
}

function jarPath(ctx) {
  const layout = backendLayout(ctx)
  return layout.kind === 'split' ? layout.appJar : layout.jar
}

function javaLaunchArgs(ctx) {
  const layout = backendLayout(ctx)
  if (layout.kind === 'fat') return ['-jar', layout.jar]
  // -cp 通配符由 JVM 展开（spawn 无 shell，不会被 glob）；分隔符按平台
  const sep = process.platform === 'win32' ? ';' : ':'
  return ['-cp', layout.appJar + sep + path.join(layout.libDir, '*'), BACKEND_MAIN_CLASS]
}

function bundledJava(ctx) {
  return path.join(ctx.resourcesPath, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
}

// dev 态无 jar 时现场 mvn 打包
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
  // 端口必须以 ctx.ports.backend 为准，不能像上面 SPRING_PROFILES_ACTIVE 那样
  // "env 里已经有就不覆盖"：ServiceManager.waitStartOrExit 轮询就绪、渲染层注入的
  // apiBaseUrl，用的都是 allocateBackendPort() 已经选定并写进 ctx.ports.backend
  // 的那一个端口；如果继承自宿主 shell/环境的 SERVER_PORT（不少框架的常见环境变量
  // 名，用户从终端启动或某些托管环境里就可能已经被设过）先占了这个位置，JVM 会
  // 绑到那个不相关的端口上，而 ServiceManager 还在死等 ctx.ports.backend 那个端口
  // 开放——探测永远等不到，白等一整个 startTimeoutMs 后把明明健康的 JVM 当启动
  // 失败杀掉重试。真要强制指定端口，走的是上面 allocateBackendPort() 里专门开的
  // CHECKBA_BACKEND_PORT 口子（会正确流入 ctx.ports.backend），不需要也不该再认
  // 环境里裸的 SERVER_PORT。
  env.SERVER_PORT = String(ctx.ports.backend)
  // 其他本地服务的动态端口注入（Spring SystemEnvironmentPropertySource 将
  // external.pptx-service.base-url 解析为 EXTERNAL_PPTX_SERVICE_BASE_URL）
  if (ctx.ports['pptx-service']) {
    env.EXTERNAL_PPTX_SERVICE_BASE_URL = 'http://127.0.0.1:' + ctx.ports['pptx-service']
  }
  // 语音合成只有本机 Kokoro 一条路；地址为空后端就报「组件未就绪」
  if (ctx.ports['kokoro-service']) {
    env.EXTERNAL_TTS_LOCAL_BASE_URL = 'http://127.0.0.1:' + ctx.ports['kokoro-service']
  }
  // 本地转写（faster-whisper）。**只注地址、不注档位**：本地档要先下 1.5GB 模型，
  // 必须由用户主动打开「录音不出本机」；像 TTS 那样打包即默认会让全新安装一开箱
  // 就转不了写（Kokoro 那边的模型只有 300MB 且组件管理里默认装）。
  if (ctx.ports['asr-service']) {
    env.EXTERNAL_ASR_LOCAL_BASE_URL = 'http://127.0.0.1:' + ctx.ports['asr-service']
  }
  // 打包态资源定位。dev 态一律不注入：后端 cwd 是 backend/，自己就能按相对路径找到
  // skills/ 与 ../litviz，注入反而会把开发中的改动指到别处去。
  if (ctx.packaged) {
    const res = ctx.resourcesPath
    // 随发行版分发的只读内置 skill 目录。打包态后端 cwd 是用户数据目录，
    // ai.skills.dir 的相对值 'skills' 会解析到 <userData>/skills（广场安装落点），
    // 内置 skill 必须另指一条只读路径过去——v0.11.1 以前没有这一条，
    // backend/skills/ 压根没进过安装包，两个内置 skill 在发行版里等于不存在。
    if (!env.AI_SKILLS_BUILTIN_DIR) {
      env.AI_SKILLS_BUILTIN_DIR = path.join(res, 'skills')
    }
    // 诉讼可视化引擎（litviz）与它要用的解释器。引擎是纯 stdlib，
    // 直接复用已经为 pysvc 打进来的那个 Python 3.11，不额外带运行时。
    // litviz 摘出安装包后（native pack，见 docs/NATIVE_PACK_DISTRIBUTION.md）
    // Resources/litviz 不一定存在，加 existsSync 守卫与 graphviz 那条对齐——
    // 目录不存在就不注入，让后端走自己的解析链（pack 目录等）。
    const litvizDir = path.join(res, 'litviz')
    if (!env.LITVIZ_DIR && fs.existsSync(litvizDir)) env.LITVIZ_DIR = litvizDir
    if (!env.AWD_PYTHON_HOME) env.AWD_PYTHON_HOME = path.join(res, 'python')
    // graphviz 只有流程图布局要用；没打进来时留空，后端会如实降级报缺。
    const gvBin = path.join(res, 'graphviz', 'bin')
    if (!env.LITVIZ_GRAPHVIZ_DIR && fs.existsSync(gvBin)) {
      env.LITVIZ_GRAPHVIZ_DIR = gvBin
    }
  }
  // 匿名使用统计的版本标注（版本单一来源 desktop/package.json，经 electron app 读取）
  if (!env.AWD_APP_VERSION) {
    try { env.AWD_APP_VERSION = require('electron').app.getVersion() } catch (e) { /* 非 electron 环境跳过 */ }
  }
  // 后端 build 指纹：wizard 端点回显，复用前对比识破陈旧残留后端（见 backendBuildId）
  env.AWD_BACKEND_BUILD = backendBuildId(ctx)
  return env
}

function spec(ctx, javaCmd) {
  // 打包模式：工作目录必须可写（resources 目录只读）
  const cwd = ctx.packaged ? ctx.dataDir : path.join(ctx.projectRoot, 'backend')
  if (ctx.packaged) fs.mkdirSync(cwd, { recursive: true })
  return { cmd: javaCmd, args: javaLaunchArgs(ctx), env: spawnEnv(ctx), cwd }
}

// 系统 JDK 回退候选（仅打包态、仅当捆绑 JRE 启动失败时才会被用到）
// 专为捆绑 JRE 启动即崩的机器（如 SIP 关闭 + amfi 的 macOS，JDK-8326663 SIGBUS）
function systemJavaFallbacks() {
  const out = []
  const exe = process.platform === 'win32' ? 'java.exe' : 'java'
  if (process.platform === 'darwin') {
    // 经 java_home 解析本机 JDK（避开 /usr/bin/java 安装存根弹窗）
    for (const args of [['-v', '21'], []]) {
      try {
        const home = execFileSync('/usr/libexec/java_home', args, { encoding: 'utf8' }).trim()
        if (home) out.push(path.join(home, 'bin', exe))
      } catch (e) {
        // 该版本/任意 JDK 不存在，忽略
      }
    }
  } else {
    // Windows/Linux：优先 JAVA_HOME，再退 PATH
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
    port: (ctx) => allocateBackendPort(ctx),
    // 占用端口的监听者必须验明正身才可复用（防"粘"到陌生进程）。
    // 打包态额外校验 build 指纹：陈旧残留后端先定点终止（成功则返回 false 让
    // 管理器重走分配链原地重启；失败则退而复用，理由见 allocateBackendPort）。
    verifyReuse: async (ctx, port) => {
      const verdict = decideReuse(await probeBackend(port), {
        packaged: ctx.packaged, expectedBuild: backendBuildId(ctx),
      })
      if (verdict === 'reuse') return true
      if (verdict === 'skip') return false
      const freed = await terminateStaleBackend(port)
      if (freed) return false
      console.warn(`[backend] 端口 ${port} 上的陈旧后端无法终止，退而复用（功能可能落后于本体版本）`)
      return true
    },
    // 复用校验失败（分配后被抢）时重新走一遍分配链
    reallocatePort: (ctx) => allocateBackendPort(ctx),
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

module.exports = {
  createBackendDescriptor, backendLayout, javaLaunchArgs,
  // 复用版本闸的可测面（backend-reuse-gate.test.js）
  decideReuse, probeBackend, backendBuildId,
}
