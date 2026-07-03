const path = require('path')
const fs = require('fs')
const { spawnSync } = require('child_process')
const { findFreePort } = require('./service-manager')

function pyBin(ctx) {
  return process.platform === 'win32'
    ? path.join(ctx.resourcesPath, 'python', 'python.exe')
    : path.join(ctx.resourcesPath, 'python', 'bin', 'python3.11')
}

function appDir(ctx) {
  return path.join(ctx.resourcesPath, 'pysvc', 'pptx-service', 'app')
}

function libDir(ctx) {
  return path.join(ctx.resourcesPath, 'pysvc', 'pptx-service', 'lib')
}

function dataDir(ctx) {
  return path.join(ctx.dataDir, 'pptx')
}

function spawnEnv(ctx) {
  const env = {
    ...process.env,
    PYTHONPATH: libDir(ctx),
    PPTX_DATA_DIR: dataDir(ctx),
    PORT: String(ctx.ports['pptx-service']),
    FLASK_ENV: 'production'
  }
  // 本地 MinerU 动态端口 + 云端兜底默认关闭（设计 §2.4 出网收口；可用 env 显式放开）
  if (ctx.ports['mineru-service']) {
    env.MINERU_LOCAL_URL = 'http://127.0.0.1:' + ctx.ports['mineru-service']
  }
  if (!env.MINERU_FORCE_CLOUD) {
    env.MINERU_FORCE_CLOUD = process.env.CHECKBA_MINERU_FORCE_CLOUD || '0'
  }
  return env
}

function createPptxDescriptor() {
  return {
    name: 'pptx-service',
    // Phase 1 有意 eager（轻量 Flask；lazy 机制随 Phase 2 mineru 落地，见设计文档 §2.1）
    eager: true,
    logName: 'pptx-service',
    // dev 态不 spawn（commands 返回空）：沿用现状——docker compose 起在 5001，复用检测直接 reuse
    port: async (ctx) => {
      if (process.env.CHECKBA_PPTX_PORT) return Number(process.env.CHECKBA_PPTX_PORT)
      // dev 态固定 5001（对齐 application.yml 默认值与 docker 映射）；打包态动态挑空闲端口
      return ctx.packaged ? findFreePort() : 5001
    },
    startTimeoutMs: () => 30000,
    prepare: async (ctx) => {
      if (!ctx.packaged) return
      fs.mkdirSync(dataDir(ctx), { recursive: true })
      // SQLite schema 迁移（alembic.ini/migrations 随 app 源码打包；env.py 经 create_app
      // 读 PPTX_DATA_DIR，迁移与运行落同一个库）
      const r = spawnSync(pyBin(ctx), ['-m', 'alembic', '-c', path.join(appDir(ctx), 'alembic.ini'), 'upgrade', 'head'], {
        cwd: appDir(ctx),
        env: spawnEnv(ctx),
        stdio: 'pipe',
        encoding: 'utf8'
      })
      if (r.status !== 0) {
        throw new Error(`pptx alembic migration failed: ${(r.stderr || '').slice(-2000)}`)
      }
    },
    commands: (ctx) => {
      if (!ctx.packaged) return [] // dev 态只做复用检测，不自行 spawn
      return [{
        cmd: pyBin(ctx),
        args: [path.join(appDir(ctx), 'app.py')],
        env: spawnEnv(ctx),
        cwd: dataDir(ctx)
      }]
    }
  }
}

module.exports = { createPptxDescriptor }
