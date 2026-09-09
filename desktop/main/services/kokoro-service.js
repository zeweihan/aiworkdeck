// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const path = require('path')
const fs = require('fs')
const { findFreePort } = require('./service-manager')
const { resolveServiceRoot, libDirFor, appDirFor } = require('./pysvc-runtime')

function pyBin(ctx) {
  return process.platform === 'win32'
    ? path.join(ctx.resourcesPath, 'python', 'python.exe')
    : path.join(ctx.resourcesPath, 'python', 'bin', 'python3.11')
}

function libDir(ctx) {
  return libDirFor(ctx, 'kokoro-service')
}

function appDir(ctx) {
  return appDirFor(ctx, 'kokoro-service')
}

function modelsDir(ctx) {
  return path.join(ctx.dataDir, 'models', 'kokoro')
}

function workDir(ctx) {
  return path.join(ctx.dataDir, 'kokoro')
}

function spawnEnv(ctx) {
  return {
    ...process.env,
    PYTHONPATH: libDir(ctx),
    PORT: String(ctx.ports['kokoro-service']),
    // 模型缓存与 ModelManager 下载侧一致；运行时强制离线（模型已预下载，零出网）
    HF_HOME: modelsDir(ctx),
    HF_HUB_OFFLINE: '1'
  }
}

// modelManager 由 main.js 注入：模型未下载时不 eager 启动（组件管理页下载后自动拉起）
function createKokoroDescriptor(modelManager) {
  return {
    name: 'kokoro-service',
    eager: true,
    logName: 'kokoro-service',
    // 运行时 pack 与模型两个门都要过：模型在但 lib 不在，spawn 出去只会 ModuleNotFoundError
    enabled: (ctx) => !ctx.packaged
      || (!!resolveServiceRoot(ctx, 'kokoro-service') && modelManager.isInstalled('kokoro-models')),
    port: async (ctx) => {
      if (process.env.CHECKBA_KOKORO_PORT) return Number(process.env.CHECKBA_KOKORO_PORT)
      // dev 态固定 8880（Kokoro 生态惯例端口）；打包态动态挑空闲端口
      return ctx.packaged ? findFreePort() : 8880
    },
    // 首次合成才加载模型，启动本身轻量；留余量给慢盘
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

module.exports = { createKokoroDescriptor }
