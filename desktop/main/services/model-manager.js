const path = require('path')
const fs = require('fs')
const { spawn } = require('child_process')

/**
 * ModelManager：本地大模型组件的状态机（absent | downloading | installed | error）。
 * 不自研下载器——spawn MinerU 官方 CLI（ModelScope/HF SDK 原生断点续传与校验），
 * 只负责：进程管理、stdout 进度解析（节流）、完成标记、删除与查询。
 *
 * createModelManager({ dataDir, resourcesPath, packaged, onProgress, spawnSpecOverride? })
 *   - onProgress({ id, phase: 'progress'|'done'|'error', percent?, message? })
 *   - spawnSpecOverride(component, ctx) → {cmd,args,env,cwd}：测试注入用
 */

const MARKER = '.aiworkdeck-complete'
const PROGRESS_THROTTLE_MS = 500

function mineruLib(resourcesPath) {
  return path.join(resourcesPath || '', 'pysvc', 'mineru-service', 'lib')
}

function pyBin(resourcesPath) {
  return process.platform === 'win32'
    ? path.join(resourcesPath || '', 'python', 'python.exe')
    : path.join(resourcesPath || '', 'python', 'bin', 'python3.11')
}

// 组件注册表：MinerU pipeline 模型 + Kokoro 语音模型
const COMPONENTS = [
  {
    id: 'mineru-models',
    name: '文档解析模型（MinerU）',
    sizeHint: '约 3GB',
    // 模型根目录（相对 dataDir）
    dir: (ctx) => path.join(ctx.dataDir, 'models', 'mineru'),
    // 官方下载 CLI（前置校证 P1/P2，见 phase2 计划）
    spawnSpec: (ctx) => {
      const dir = path.join(ctx.dataDir, 'models', 'mineru')
      return {
        cmd: pyBin(ctx.resourcesPath),
        args: [
          '-c', 'from mineru.cli.models_download import download_models; download_models()',
          '-s', 'modelscope', '-m', 'pipeline'
        ],
        env: {
          ...process.env,
          PYTHONPATH: mineruLib(ctx.resourcesPath),
          MINERU_MODEL_SOURCE: 'modelscope',
          MODELSCOPE_CACHE: dir,
          HF_HOME: path.join(dir, 'hf'),
          MINERU_TOOLS_CONFIG_JSON: path.join(dir, 'mineru.json')
        },
        cwd: dir
      }
    }
  },
  {
    id: 'kokoro-models',
    name: '语音合成模型（Kokoro）',
    sizeHint: '约 300MB',
    dir: (ctx) => path.join(ctx.dataDir, 'models', 'kokoro'),
    // huggingface_hub snapshot（走国内镜像，env 可覆盖）；运行侧 HF_HOME 与此一致
    spawnSpec: (ctx) => {
      const dir = path.join(ctx.dataDir, 'models', 'kokoro')
      return {
        cmd: pyBin(ctx.resourcesPath),
        args: [
          '-c',
          "from huggingface_hub import snapshot_download; snapshot_download('hexgrad/Kokoro-82M-v1.1-zh')"
        ],
        env: {
          ...process.env,
          PYTHONPATH: path.join(ctx.resourcesPath || '', 'pysvc', 'kokoro-service', 'lib'),
          HF_HOME: dir,
          HF_ENDPOINT: process.env.CHECKBA_HF_ENDPOINT || 'https://hf-mirror.com'
        },
        cwd: dir
      }
    }
  }
]

class ModelManager {
  constructor(opts) {
    this.ctx = {
      dataDir: opts.dataDir,
      resourcesPath: opts.resourcesPath,
      packaged: !!opts.packaged
    }
    this.onProgress = opts.onProgress || (() => {})
    this.spawnSpecOverride = opts.spawnSpecOverride || null
    this.active = new Map() // id -> child process
    this.errors = new Map() // id -> last error message
  }

  component(id) {
    const c = COMPONENTS.find((x) => x.id === id)
    if (!c) throw new Error(`unknown component: ${id}`)
    return c
  }

  dirOf(id) {
    return this.component(id).dir(this.ctx)
  }

  isInstalled(id) {
    return fs.existsSync(path.join(this.dirOf(id), MARKER))
  }

  stateOf(id) {
    if (this.active.has(id)) return 'downloading'
    if (this.isInstalled(id)) return 'installed'
    if (this.errors.has(id)) return 'error'
    return 'absent'
  }

  status() {
    return COMPONENTS.map((c) => ({
      id: c.id,
      name: c.name,
      sizeHint: c.sizeHint,
      state: this.stateOf(c.id),
      message: this.errors.get(c.id) || null
    }))
  }

  async download(id) {
    const c = this.component(id)
    if (this.active.has(id)) throw new Error(`${id} already downloading`)
    if (this.isInstalled(id)) throw new Error(`${id} already installed`)
    this.errors.delete(id)

    const dir = this.dirOf(id)
    fs.mkdirSync(dir, { recursive: true })
    const spec = this.spawnSpecOverride ? this.spawnSpecOverride(c, this.ctx) : c.spawnSpec(this.ctx)
    const child = spawn(spec.cmd, spec.args, { cwd: spec.cwd, env: spec.env, stdio: ['ignore', 'pipe', 'pipe'] })
    this.active.set(id, child)

    let lastEmit = 0
    let lastLine = ''
    const onLine = (line) => {
      lastLine = line
      const m = line.match(/(\d{1,3})%/)
      const now = Date.now()
      if (now - lastEmit < PROGRESS_THROTTLE_MS) return
      lastEmit = now
      this.onProgress({
        id,
        phase: 'progress',
        percent: m ? Math.min(100, Number(m[1])) : undefined,
        message: line.slice(0, 200)
      })
    }
    const hook = (stream) => {
      let buf = ''
      stream.on('data', (d) => {
        buf += d.toString()
        // tqdm 用 \r 刷新进度，按 \r 和 \n 一起切行
        const parts = buf.split(/[\r\n]+/)
        buf = parts.pop()
        for (const p of parts) if (p.trim()) onLine(p.trim())
      })
    }
    hook(child.stdout)
    hook(child.stderr)

    child.on('exit', (code, signal) => {
      const wasCancelled = child._awdCancelled
      this.active.delete(id)
      if (wasCancelled) {
        this.onProgress({ id, phase: 'progress', percent: 0, message: 'cancelled' })
        return
      }
      if (code === 0) {
        fs.writeFileSync(path.join(dir, MARKER), new Date().toISOString())
        this.onProgress({ id, phase: 'done' })
      } else {
        const msg = `download exited ${code ?? signal}: ${lastLine}`.slice(0, 500)
        this.errors.set(id, msg)
        this.onProgress({ id, phase: 'error', message: msg })
      }
    })
    child.on('error', (e) => {
      this.active.delete(id)
      const msg = String(e && e.message ? e.message : e)
      this.errors.set(id, msg)
      this.onProgress({ id, phase: 'error', message: msg })
    })

    return { ok: true }
  }

  async cancel(id) {
    const child = this.active.get(id)
    if (!child) return { ok: true }
    child._awdCancelled = true
    return new Promise((resolve) => {
      let done = false
      const finish = () => {
        if (done) return
        done = true
        this.active.delete(id)
        this.errors.delete(id)
        resolve({ ok: true })
      }
      child.once('exit', finish)
      try { child.kill('SIGTERM') } catch (e) { finish(); return }
      setTimeout(() => {
        try { child.kill('SIGKILL') } catch (e) { /* ignore */ }
        finish()
      }, 3000)
    })
  }

  /** 退出时批量终止所有进行中的下载子进程，防止孤儿 Python 进程继续占用 CPU/带宽/磁盘。 */
  killAllActive() {
    for (const [, child] of this.active) {
      try { child._awdCancelled = true; child.kill('SIGTERM') } catch (e) { /* ignore */ }
    }
    this.active.clear()
  }

  async remove(id) {
    if (this.active.has(id)) await this.cancel(id)
    fs.rmSync(this.dirOf(id), { recursive: true, force: true })
    this.errors.delete(id)
    return { ok: true }
  }
}

function createModelManager(opts) {
  return new ModelManager(opts)
}

module.exports = { createModelManager, ModelManager }
