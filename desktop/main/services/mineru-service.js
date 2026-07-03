const path = require('path')
const fs = require('fs')
const { findFreePort } = require('./service-manager')

function pyBin(ctx) {
  return process.platform === 'win32'
    ? path.join(ctx.resourcesPath, 'python', 'python.exe')
    : path.join(ctx.resourcesPath, 'python', 'bin', 'python3.11')
}

function libDir(ctx) {
  return path.join(ctx.resourcesPath, 'pysvc', 'mineru-service', 'lib')
}

function modelsDir(ctx) {
  return path.join(ctx.dataDir, 'models', 'mineru')
}

function workDir(ctx) {
  return path.join(ctx.dataDir, 'mineru')
}

function spawnEnv(ctx) {
  return {
    ...process.env,
    PYTHONPATH: libDir(ctx),
    MINERU_DEVICE_MODE: 'cpu',
    MINERU_MODEL_SOURCE: 'modelscope',
    // 模型缓存与配置定位（前置校证 P2，与 ModelManager 下载侧完全一致）
    MODELSCOPE_CACHE: modelsDir(ctx),
    HF_HOME: path.join(modelsDir(ctx), 'hf'),
    MINERU_TOOLS_CONFIG_JSON: path.join(modelsDir(ctx), 'mineru.json')
  }
}

// modelManager 由 main.js 注入：模型未下载时不 eager 启动（组件管理页下载后再拉起）
function createMineruDescriptor(modelManager) {
  return {
    name: 'mineru-service',
    eager: true,
    logName: 'mineru-service',
    enabled: (ctx) => !ctx.packaged || modelManager.isInstalled('mineru-models'),
    port: async (ctx) => {
      if (process.env.CHECKBA_MINERU_PORT) return Number(process.env.CHECKBA_MINERU_PORT)
      // dev 态固定 8001（对齐 docker-compose 映射）；打包态动态挑空闲端口
      return ctx.packaged ? findFreePort() : 8001
    },
    // 模型加载慢（CPU 首启可达分钟级）
    startTimeoutMs: () => 180000,
    prepare: async (ctx) => {
      if (!ctx.packaged) return
      fs.mkdirSync(workDir(ctx), { recursive: true })
    },
    commands: (ctx) => {
      if (!ctx.packaged) return [] // dev 态只做复用检测（docker 起在 8001），不自行 spawn
      return [{
        cmd: pyBin(ctx),
        args: ['-m', 'mineru.cli.fast_api', '--host', '127.0.0.1', '--port', String(ctx.ports['mineru-service'])],
        env: spawnEnv(ctx),
        cwd: workDir(ctx)
      }]
    }
  }
}

module.exports = { createMineruDescriptor }
