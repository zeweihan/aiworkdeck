const path = require('path')
const net = require('net')
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
      try {
        socket.destroy()
      } catch (e) {
        // ignore
      }
      resolve(ok)
    }
    socket.setTimeout(timeoutMs)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(port, host)
  })
}

async function waitPort(port, timeoutMs = 12000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    // eslint-disable-next-line no-await-in-loop
    const ok = await isPortOpen(port)
    if (ok) return true
    // eslint-disable-next-line no-await-in-loop
    await sleep(200)
  }
  return false
}

class BackendManager {
  constructor(options) {
    const opts = options || {}
    const projectRoot = opts.projectRoot ? opts.projectRoot : process.cwd()
    this.projectRoot = projectRoot
    this.packaged = !!opts.packaged
    if (this.packaged) {
      // 打包模式（Epic #18 T2）：jar + 裁剪版 JRE 由 electron-builder extraResources
      // 捆绑进安装包（见 desktop/scripts/prepare-backend.js），数据与日志落在 ~/.aiworkdeck
      // （与 desktop profile 的 H2 库同目录）
      const resourcesPath = opts.resourcesPath || process.resourcesPath
      this.dataDir = opts.dataDir || path.join(require('os').homedir(), '.aiworkdeck')
      this.backendDir = this.dataDir
      this.jarPath = path.join(resourcesPath, 'backend', 'backend.jar')
      this.javaCmd = path.join(resourcesPath, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
      this.defaultProfile = 'desktop'
      this.startTimeoutMs = 60000
    } else {
      this.backendDir = path.join(projectRoot, 'backend')
      this.jarPath = path.join(this.backendDir, 'target', 'backend-0.0.1-SNAPSHOT.jar')
      this.javaCmd = process.platform === 'win32' ? 'java.exe' : 'java'
      this.defaultProfile = 'prod'
      this.startTimeoutMs = 15000
    }
    this.port = Number(process.env.CHECKBA_BACKEND_PORT || 9696)
    this.proc = null
  }

  async ensureJar() {
    const fs = require('fs')
    if (fs.existsSync(this.jarPath)) return
    if (this.packaged) {
      throw new Error(`bundled backend jar missing: ${this.jarPath}`)
    }
    await this.buildJar()
  }

  buildJar() {
    return new Promise((resolve, reject) => {
      const mvnCmd = process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
      const p = spawn(mvnCmd, ['-q', '-DskipTests', 'package'], {
        cwd: this.backendDir,
        stdio: 'inherit'
      })
      p.on('exit', (code) => {
        if (code === 0) resolve()
        else reject(new Error(`mvn package failed: ${code}`))
      })
      p.on('error', (e) => reject(e))
    })
  }

  async start() {
    // 已有后端在跑：直接复用
    if (await isPortOpen(this.port)) {
      return { ok: true, reused: true }
    }

    await this.ensureJar()

    // 再次确认端口（避免 race）
    if (await isPortOpen(this.port)) {
      return { ok: true, reused: true }
    }

    if (this.proc) {
      // 之前我们启动过但端口不通：先清理
      await this.stop()
    }

    const env = { ...process.env }
    // 开发态默认 prod profile，打包态默认 desktop（H2 单机库），仍可用 env 覆盖
    if (!env.SPRING_PROFILES_ACTIVE) env.SPRING_PROFILES_ACTIVE = this.defaultProfile
    // 统一端口（仍可用配置文件覆盖）
    if (!env.SERVER_PORT) env.SERVER_PORT = String(this.port)

    let stdio = 'inherit'
    let logStream = null
    if (this.packaged) {
      // 打包模式：工作目录必须可写（resources 目录只读），日志落盘便于排障
      const fs = require('fs')
      fs.mkdirSync(this.backendDir, { recursive: true })
      logStream = fs.createWriteStream(path.join(this.backendDir, 'backend.log'), { flags: 'a' })
      stdio = ['ignore', 'pipe', 'pipe']
    }

    this.proc = spawn(this.javaCmd, ['-jar', this.jarPath], {
      cwd: this.backendDir,
      env,
      stdio
    })
    if (logStream) {
      this.proc.stdout.pipe(logStream)
      this.proc.stderr.pipe(logStream)
    }

    const ok = await waitPort(this.port, this.startTimeoutMs)
    if (!ok) {
      try {
        await this.stop()
      } catch (e) {
        // ignore
      }
      throw new Error(`backend failed to start on port ${this.port}`)
    }
    return { ok: true, reused: false }
  }

  async stop() {
    if (!this.proc) return { ok: true }
    const p = this.proc
    this.proc = null

    return new Promise((resolve) => {
      let finished = false
      const done = () => {
        if (finished) return
        finished = true
        resolve({ ok: true })
      }

      p.once('exit', () => done())
      try {
        p.kill('SIGTERM')
      } catch (e) {
        done()
        return
      }
      // 兜底：3s 后强杀
      setTimeout(() => {
        try {
          p.kill('SIGKILL')
        } catch (e) {
          // ignore
        }
        done()
      }, 3000)
    })
  }

  async restart() {
    await this.stop()
    return this.start()
  }
}

module.exports = { BackendManager }


