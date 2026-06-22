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

    // 候选 java：优先捆绑 JRE；仅当其启动失败时，才（惰性）解析系统 JDK 作为回退。
    // 普通用户捆绑 JRE 正常启动 → 端口先就绪 → 回退分支永不进入（happy path 零开销、CI 冒烟不受影响）。
    // 回退专为捆绑 JRE 启动即崩的机器（如 SIP 关闭 + amfi 的 macOS，JDK-8326663 SIGBUS）。
    const candidates = [this.javaCmd]

    for (let i = 0; i < candidates.length; i++) {
      const javaCmd = candidates[i]
      this.proc = spawn(javaCmd, ['-jar', this.jarPath], {
        cwd: this.backendDir,
        env,
        stdio
      })
      if (logStream) {
        this.proc.stdout.pipe(logStream)
        this.proc.stderr.pipe(logStream)
      }

      // 等"端口就绪"或"进程提前退出（崩溃）"，谁先到算谁
      // eslint-disable-next-line no-await-in-loop
      const result = await this.waitStartOrExit(this.proc, this.startTimeoutMs)
      if (result === 'started') {
        return { ok: true, reused: false, javaCmd }
      }
      if (result === 'timeout') {
        // 进程还活着但端口没起：杀掉再试下一个候选
        try {
          // eslint-disable-next-line no-await-in-loop
          await this.stop()
        } catch (e) {
          // ignore
        }
      } else {
        // 'exited'：进程已崩溃退出，丢引用即可，继续尝试下一个候选
        this.proc = null
      }

      // 捆绑 JRE（首个候选）启动失败后，才解析系统 JDK 追加为回退候选
      if (this.packaged && i === 0) {
        for (const c of this.systemJavaFallbacks()) {
          if (!candidates.includes(c)) candidates.push(c)
        }
      }
    }

    throw new Error(`backend failed to start on port ${this.port}`)
  }

  // 等后端端口就绪或进程退出，返回 'started' | 'exited' | 'timeout'
  waitStartOrExit(proc, timeoutMs) {
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
          if (await isPortOpen(this.port)) return finish('started')
          // eslint-disable-next-line no-await-in-loop
          await sleep(200)
        }
        finish('timeout')
      }
      poll()
    })
  }

  // 系统 JDK 回退候选（仅打包态、仅当捆绑 JRE 启动失败时才会被用到）
  systemJavaFallbacks() {
    const out = []
    const exe = process.platform === 'win32' ? 'java.exe' : 'java'
    if (process.platform === 'darwin') {
      // 经 java_home 解析本机 JDK（避开 /usr/bin/java 安装存根弹窗）
      const { execFileSync } = require('child_process')
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


