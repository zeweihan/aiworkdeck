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
const PROGRESS_POLL_MS = 1000

// 目标目录累计字节数（含子目录）：整体进度 = 已落盘字节 / estBytes。
// 下载器的 stdout 百分比是"当前单个文件"的 tqdm（MinerU 十几个模型文件会
// 反复 0→100%，真机反馈以为下载重跑了），不能当整体进度用。
function dirSize(root) {
  let total = 0
  const stack = [root]
  while (stack.length) {
    const cur = stack.pop()
    let entries
    try { entries = fs.readdirSync(cur, { withFileTypes: true }) } catch (e) { continue }
    for (const en of entries) {
      const fp = path.join(cur, en.name)
      if (en.isDirectory()) stack.push(fp)
      else if (en.isFile()) { try { total += fs.statSync(fp).size } catch (e) { /* 下载器可能正在改名/删除 */ } }
    }
  }
  return total
}

const { pysvcPath } = require('./pysvc-runtime')

function pyBin(resourcesPath) {
  return process.platform === 'win32'
    ? path.join(resourcesPath || '', 'python', 'python.exe')
    : path.join(resourcesPath || '', 'python', 'bin', 'python3.11')
}

// 组件注册表：MinerU pipeline 模型 + Kokoro 语音模型 + 本地转写模型
// sizeHint 只写数值与单位、**不带任何语言**（不要写「约」）：它会被塞进
// admin 的下载/删除确认文案与语音面板的按钮里，那些串是双语的，
// 「约」写在这里就会原样出现在英文界面上。修饰词归各处的 i18n 串。
const COMPONENTS = [
  {
    id: 'mineru-models',
    name: '文档解析模型（MinerU）',
    sizeHint: '3 GB',
    estBytes: 3.0 * 1024 * 1024 * 1024, // 整体进度分母（估计值，进度封顶 99% 直到进程成功退出）
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
          PYTHONPATH: pysvcPath(ctx, 'mineru-service', 'lib'),
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
    sizeHint: '300 MB',
    estBytes: 300 * 1024 * 1024,
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
          PYTHONPATH: pysvcPath(ctx, 'kokoro-service', 'lib'),
          HF_HOME: dir,
          HF_ENDPOINT: process.env.CHECKBA_HF_ENDPOINT || 'https://hf-mirror.com',
          // 打包内带 hf_xet：Xet 路径绕过 HF_ENDPOINT 直连 HF 官方 CAS
          // (cas-server.xethub.hf.co)，镜像签发的凭证在那边必 401——大陆用户
          // 无梯子即挂。禁用 xet 走镜像的普通 HTTP 下载（真机 401 实证）。
          HF_HUB_DISABLE_XET: '1'
        },
        cwd: dir
      }
    }
  },
  {
    id: 'asr-models',
    name: '本地转写模型（Whisper medium）',
    sizeHint: '1.5 GB',
    estBytes: 1.5 * 1024 * 1024 * 1024,
    dir: (ctx) => path.join(ctx.dataDir, 'models', 'asr'),
    // 与 kokoro 同一条 huggingface_hub snapshot 路径；运行侧 HF_HOME 与此一致。
    // 模型不进安装包：1.5GB 会让安装包体积翻几倍，而只有开了「录音不出本机」的用户才需要它。
    spawnSpec: (ctx) => {
      const dir = path.join(ctx.dataDir, 'models', 'asr')
      return {
        cmd: pyBin(ctx.resourcesPath),
        args: [
          '-c',
          "from huggingface_hub import snapshot_download; snapshot_download('Systran/faster-whisper-medium')"
        ],
        env: {
          ...process.env,
          PYTHONPATH: pysvcPath(ctx, 'asr-service', 'lib'),
          HF_HOME: dir,
          HF_ENDPOINT: process.env.CHECKBA_HF_ENDPOINT || 'https://hf-mirror.com',
          // 同 kokoro：Xet 路径绕过 HF_ENDPOINT 直连 HF 官方 CAS，镜像签发的凭证在那边必 401
          HF_HUB_DISABLE_XET: '1'
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
      pysvcRoot: opts.pysvcRoot || null,
      packaged: !!opts.packaged
    }
    this.onProgress = opts.onProgress || (() => {})
    this.spawnSpecOverride = opts.spawnSpecOverride || null
    this.progressPollMs = opts.progressPollMs || PROGRESS_POLL_MS
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

    // stdout/stderr 只取"最近一行"当状态文案；百分比不再取自下载器输出——
    // 那是单个文件的 tqdm，十几个模型文件会反复 0→100%（真机反馈"下载完又
    // 从 0 开始"）。整体进度 = 已落盘字节 / estBytes，按固定间隔轮询目录。
    let lastLine = ''
    const onLine = (line) => { lastLine = line }
    const estBytes = c.estBytes || 0
    const poller = setInterval(() => {
      const percent = estBytes > 0
        ? Math.min(99, Math.round(dirSize(dir) / estBytes * 100)) // 封顶 99，成功退出才是 done
        : undefined
      this.onProgress({ id, phase: 'progress', percent, message: lastLine.slice(0, 200) })
    }, this.progressPollMs)
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
      clearInterval(poller)
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
      clearInterval(poller)
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
