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
 *   verifyReuse?: (ctx, port) => Promise<boolean>  // 端口已被监听时验明是否自家服务；缺省视为是（旧语义）
 *   reallocatePort?: (ctx) => Promise<number>      // verifyReuse 失败（端口被陌生进程占）时重新分配
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
    this.starting = new Map() // name -> in-flight start promise（并发去重，防重复 spawn）
    this.logStreams = new Map() // name -> 日志写入流（跟踪以便关闭，防 fd 泄漏）
  }

  register(descriptor) {
    this.descriptors.set(descriptor.name, descriptor)
  }

  async allocatePorts() {
    for (const d of this.descriptors.values()) {
      // eslint-disable-next-line no-await-in-loop
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
    // 并发去重：同名服务的并发 start 复用同一 in-flight promise，防止重复 spawn 产生孤儿进程、抢同一端口
    const inflight = this.starting.get(name)
    if (inflight) return inflight
    const p = this._start(name).finally(() => this.starting.delete(name))
    this.starting.set(name, p)
    return p
  }

  async _start(name) {
    const d = this.descriptors.get(name)
    if (!d) throw new Error(`unknown service: ${name}`)
    if (d.enabled && !d.enabled(this.ctx)) return { ok: false, disabled: true }
    let port = this.ports[name]

    // 已有实例在跑：验明正身后复用（无 verifyReuse 的服务保持旧语义直接复用）
    // 'reuse' = 自家服务在听 | 'foreign' = 陌生进程占用 | 'free' = 无人监听
    const probe = async () => {
      if (!(await isPortOpen(port))) return 'free'
      if (!d.verifyReuse || (await d.verifyReuse(this.ctx, port))) return 'reuse'
      return 'foreign'
    }
    let state = await probe()
    if (state === 'foreign' && d.reallocatePort) {
      port = await d.reallocatePort(this.ctx)
      this.ports[name] = port
      state = await probe()
    }
    if (state === 'reuse') return { ok: true, reused: true }
    if (state === 'foreign') throw new Error(`${name} port ${port} 被未知进程占用`)

    if (d.prepare) await d.prepare(this.ctx)
    const postPrepare = await probe()
    if (postPrepare === 'reuse') return { ok: true, reused: true }
    if (postPrepare === 'foreign') throw new Error(`${name} port ${port} 被未知进程占用`)
    if (this.procs.get(name)) await this.stop(name)

    const timeoutMs = d.startTimeoutMs(this.ctx)
    // 关闭上一次 start 遗留的日志流，避免每次重启/崩溃-重启累积文件描述符泄漏
    const prevStream = this.logStreams.get(name)
    if (prevStream) { try { prevStream.end() } catch (e) { /* ignore */ } this.logStreams.delete(name) }
    const logStream = this.logStreamFor(d)
    if (logStream) this.logStreams.set(name, logStream)
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

      // 等"端口就绪"或"进程提前退出（崩溃）"，谁先到算谁
      // eslint-disable-next-line no-await-in-loop
      const result = await this.waitStartOrExit(proc, port, timeoutMs)
      if (result === 'started') return { ok: true, reused: false, cmd: spec.cmd }
      if (result === 'timeout') {
        // 进程还活着但端口没起：杀掉再试下一个候选
        try {
          // eslint-disable-next-line no-await-in-loop
          await this.stop(name)
        } catch (e) { /* ignore */ }
      } else {
        // 'exited'：进程已崩溃退出，丢引用即可，继续尝试下一个候选
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

  // 等服务端口就绪或进程退出，返回 'started' | 'exited' | 'timeout'
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
      // 兜底：3s 后强杀
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
    // 退出/全停时关闭所有日志流，释放 fd
    for (const [, s] of this.logStreams) { try { s.end() } catch (e) { /* ignore */ } }
    this.logStreams.clear()
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
