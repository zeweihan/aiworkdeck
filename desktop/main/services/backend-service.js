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
function isOurBackend(port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/api/admin/wizard', timeout: timeoutMs },
      (res) => {
        let body = ''
        res.on('data', (c) => { if (body.length < 4096) body += c })
        res.on('end', () => resolve(res.statusCode === 200 && body.includes('"initialized"')))
      }
    )
    req.on('timeout', () => { req.destroy(); resolve(false) })
    req.on('error', () => resolve(false))
  })
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
  for (const cand of DESKTOP_PORT_CHAIN) {
    // eslint-disable-next-line no-await-in-loop
    if (await isPortOpen(cand)) {
      // 有人在听：是自己的后端（上次残留/多开实例）则复用，陌生进程则跳过
      // eslint-disable-next-line no-await-in-loop
      if (await isOurBackend(cand)) return cand
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
  // 统一端口（仍可用配置文件覆盖）
  if (!env.SERVER_PORT) env.SERVER_PORT = String(ctx.ports.backend)
  // 其他本地服务的动态端口注入（Spring SystemEnvironmentPropertySource 将
  // external.pptx-service.base-url 解析为 EXTERNAL_PPTX_SERVICE_BASE_URL）
  if (ctx.ports['pptx-service']) {
    env.EXTERNAL_PPTX_SERVICE_BASE_URL = 'http://127.0.0.1:' + ctx.ports['pptx-service']
  }
  // 桌面默认本地语音（Kokoro）；ElevenLabs 可在系统管理里切回（Phase 3）
  if (ctx.ports['kokoro-service']) {
    if (!env.EXTERNAL_TTS_PROVIDER) env.EXTERNAL_TTS_PROVIDER = 'local'
    env.EXTERNAL_TTS_LOCAL_BASE_URL = 'http://127.0.0.1:' + ctx.ports['kokoro-service']
  }
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
    // 占用端口的监听者必须验明正身才可复用（防"粘"到陌生进程）
    verifyReuse: (ctx, port) => isOurBackend(port),
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

module.exports = { createBackendDescriptor, backendLayout, javaLaunchArgs }
