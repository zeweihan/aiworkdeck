const path = require('path')
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
  return { cmd: javaCmd, args: ['-jar', jarPath(ctx)], env: spawnEnv(ctx), cwd }
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
