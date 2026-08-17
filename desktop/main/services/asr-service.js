const path = require('path')
const fs = require('fs')
const { findFreePort } = require('./service-manager')
const { pysvcPath } = require('./pysvc-runtime')

function pyBin(ctx) {
  return process.platform === 'win32'
    ? path.join(ctx.resourcesPath, 'python', 'python.exe')
    : path.join(ctx.resourcesPath, 'python', 'bin', 'python3.11')
}

function libDir(ctx) {
  return pysvcPath(ctx, 'asr-service', 'lib')
}

function appDir(ctx) {
  return pysvcPath(ctx, 'asr-service', 'app')
}

function modelsDir(ctx) {
  return path.join(ctx.dataDir, 'models', 'asr')
}

function workDir(ctx) {
  return path.join(ctx.dataDir, 'asr')
}

function spawnEnv(ctx) {
  return {
    ...process.env,
    PYTHONPATH: libDir(ctx),
    PORT: String(ctx.ports['asr-service']),
    // 模型缓存与 ModelManager 下载侧一致；运行时强制离线——本地档的全部意义就是零出网，
    // 少了这一行，模型缺文件时 huggingface_hub 会去联网补，音频虽然没走但已经破了承诺
    HF_HOME: modelsDir(ctx),
    HF_HUB_OFFLINE: '1'
  }
}

/**
 * 本地 ASR（faster-whisper）。
 *
 * 与 kokoro 的关键差别：**模型没下载时照样启动**。
 * kokoro 用 `enabled` 门在模型上（没模型就不起进程），而这边的就绪探测必须能分清
 * 「服务没起」与「模型没下」——两者的下一步完全不同（重启应用 vs 下 1.5GB 模型）。
 * 不起进程的话探测只能回「服务没起」，用户按提示重启一万次也不会有模型。
 * 模型是懒加载的，空跑一个 FastAPI 进程的代价只有几十 MB 常驻内存。
 */
function createAsrDescriptor() {
  return {
    name: 'asr-service',
    eager: true,
    logName: 'asr-service',
    port: async (ctx) => {
      if (process.env.CHECKBA_ASR_PORT) return Number(process.env.CHECKBA_ASR_PORT)
      // dev 态固定 8890（与 kokoro 的 8880 相邻，便于本机联调）；打包态动态挑空闲端口
      return ctx.packaged ? findFreePort() : 8890
    },
    // 首次转写才加载模型，启动本身轻量；留余量给慢盘
    startTimeoutMs: () => 60000,
    prepare: async (ctx) => {
      if (!ctx.packaged) return
      fs.mkdirSync(workDir(ctx), { recursive: true })
    },
    commands: (ctx) => {
      if (!ctx.packaged) return [] // dev 态只做复用检测，不自行 spawn
      return [{
        cmd: pyBin(ctx),
        args: [path.join(appDir(ctx), 'app.py')],
        env: spawnEnv(ctx),
        cwd: workDir(ctx)
      }]
    }
  }
}

module.exports = { createAsrDescriptor }
