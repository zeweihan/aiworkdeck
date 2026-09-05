#!/usr/bin/env node
// 发布视频录屏流水线入口（dev-board #59）。
//
// 全链路：起隔离后端（独立 H2/user.home，端口不是 9696）→ 灌演示项目「林芳劳动争议」
// （REST 建项目/文件夹/文件，md 用 macOS textutil 转 docx）→ 起 dev H5（独立端口）→
// 起带无边框标题栏的 dev Electron 并用 puppeteer-core 通过 CDP 连上去 → 注入虚拟光标 →
// CDP 截屏收帧 → 跑场景脚本（一幕 = 一个 async 函数）→ ffmpeg 合成 30fps mp4。
//
// 用法：node run.mjs sample   （scene 名对应 src/scenes/<name>.mjs 里的默认导出同名函数）

import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { startIsolatedBackend } from './src/backend.mjs'
import { seedDemoProject } from './src/demo-project.mjs'
import { launchElectron } from './src/electron.mjs'
import { installCursor } from './src/cursor.mjs'
import { startRecording } from './src/recorder.mjs'
import { Stage } from './src/stage.mjs'
import { BACKEND_PORT, DEVSERVER_PORT, DEVSERVER_URL, FRONTEND_DIR, OUT_DIR } from './src/config.mjs'

const sceneName = process.argv[2] || 'sample'

async function startDevServer() {
  const logPath = path.join(OUT_DIR, `devserver-${Date.now()}.log`)
  fs.mkdirSync(OUT_DIR, { recursive: true })
  const logStream = fs.createWriteStream(logPath)
  const child = spawn('npx', ['uni', '--port', String(DEVSERVER_PORT)], {
    cwd: FRONTEND_DIR,
    env: {
      ...process.env,
      // 走 Electron 壳时渲染层实际用的是 CHECKBA_BACKEND_PORT 注入（见 electron.mjs），
      // 这里仍然设置 VITE_API_BASE_URL 保持环境一致、也让降级到浏览器目标时行为不变。
      VITE_API_BASE_URL: `http://127.0.0.1:${BACKEND_PORT}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
  })
  child.stdout.pipe(logStream)
  child.stderr.pipe(logStream)
  const killTree = () => {
    for (const sig of ['SIGTERM', 'SIGKILL']) {
      try { process.kill(-child.pid, sig) } catch (e) { /* 组已经没了 */ }
    }
  }
  process.on('exit', killTree)

  const started = Date.now()
  while (Date.now() - started < 120000) {
    try {
      const r = await fetch(DEVSERVER_URL)
      if (r.status < 500) { return { kill: killTree, logPath } }
    } catch (e) { /* 未就绪 */ }
    await new Promise((r) => setTimeout(r, 1000))
  }
  killTree()
  throw new Error(`dev H5 (${DEVSERVER_URL}) 120s 内未就绪，日志见 ${logPath}`)
}

async function main() {
  const sceneModule = await import(`./src/scenes/${sceneName}.mjs`)
  const sceneFn = sceneModule[`${sceneName}Scene`] || sceneModule.default
  if (typeof sceneFn !== 'function') {
    throw new Error(`src/scenes/${sceneName}.mjs 没有导出 ${sceneName}Scene 或 default 函数`)
  }

  console.log('[1/6] 起隔离后端...')
  const backend = await startIsolatedBackend({ port: BACKEND_PORT, tag: sceneName })
  console.log(`  后端已就绪：${backend.baseUrl}（home=${backend.home}）`)

  console.log('[2/6] 灌演示项目「林芳劳动争议」...')
  const demo = await seedDemoProject(backend.baseUrl)

  console.log('[3/6] 起 dev H5...')
  const devServer = await startDevServer()
  console.log(`  dev H5 已就绪：${DEVSERVER_URL}`)

  console.log('[4/6] 起 dev Electron 并用 CDP 连上去...')
  const electron = await launchElectron({ backendPort: BACKEND_PORT })
  console.log(`  CDP 端口 ${electron.cdpPort}`)

  await installCursor(electron.page)

  const outDir = path.join(OUT_DIR, sceneName)
  console.log('[5/6] 开始录屏并跑场景...')
  const recording = await startRecording(electron.page, outDir)

  let result = null
  try {
    const stage = new Stage(electron.page)
    await sceneFn(stage, { ...demo })
  } finally {
    console.log('[6/6] 停止录屏，合成 mp4...')
    result = await recording.stop()
    await electron.close()
    devServer.kill()
    backend.kill()
  }

  console.log('')
  console.log('完成：')
  console.log(`  mp4: ${result.mp4Path}`)
  console.log(`  帧数: ${result.frameCount}`)
  console.log(`  后端日志: ${backend.logPath}`)
  console.log(`  dev H5 日志: ${devServer.logPath}`)
}

main().catch((err) => {
  console.error('[run.mjs] 失败:', err)
  process.exit(1)
})
