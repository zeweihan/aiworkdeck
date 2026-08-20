#!/usr/bin/env node
// 三段图卡动效渲染（dev-board #59）。
//
// 每张卡是一个自包含 HTML（1920x1080），页面内用 JS 暴露 window.renderFrame(t)，
// t = 相对片段起点的秒数，纯函数式地把 opacity/transform 设置到位——不依赖
// CSS animation/transition 的真实播放时间，所以本脚本可以逐帧步进（t 从 0 按
// 1/30 递增）截图，帧与帧之间严格对应 30fps 下的确定时刻，不会有无头浏览器
// 节流/丢帧导致的时间漂移。截完全部帧后用 ffmpeg 按 30fps 编码为 mp4。
//
// puppeteer-core 直接复用 ../pipeline/node_modules 里已装好的依赖（相对路径
// import，不在 cards/ 下另装一份 node_modules）；浏览器可执行文件用本机
// Google Chrome.app，可用 CHROME_PATH 环境变量覆盖。
//
// 用法：node render.mjs            渲染三段
//       node render.mjs card-open  只渲染一段

import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import puppeteer from '../pipeline/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const OUT_DIR = path.join(__dirname, 'out')
const FPS = 30

const DEFAULT_CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const CHROME_PATH = process.env.CHROME_PATH || DEFAULT_CHROME_PATH

const CARDS = [
  { name: 'card-open', file: 'card-open.html', duration: 14.0 },
  { name: 'card-reveal', file: 'card-reveal.html', duration: 26.0 },
  { name: 'card-close', file: 'card-close.html', duration: 12.0 },
]

function which(bin) {
  const r = spawnSync('which', [bin])
  return r.status === 0
}

async function renderCard(browser, card, framesRootDir) {
  const htmlPath = path.join(__dirname, card.file)
  if (!fs.existsSync(htmlPath)) {
    throw new Error(`找不到 ${htmlPath}`)
  }

  const framesDir = path.join(framesRootDir, card.name)
  fs.rmSync(framesDir, { recursive: true, force: true })
  fs.mkdirSync(framesDir, { recursive: true })

  const page = await browser.newPage()
  await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 })
  await page.goto('file://' + htmlPath, { waitUntil: 'load' })
  // 系统字体（Songti SC）无需等待 webfont 加载，这里仍保险等一次 document.fonts.ready
  await page.evaluate(() => document.fonts && document.fonts.ready)

  const totalFrames = Math.round(card.duration * FPS)
  console.log(`  [${card.name}] ${card.duration}s × ${FPS}fps = ${totalFrames} 帧`)

  for (let i = 0; i < totalFrames; i++) {
    const t = i / FPS
    await page.evaluate((tt) => window.renderFrame(tt), t)
    const framePath = path.join(framesDir, `f${String(i).padStart(6, '0')}.png`)
    await page.screenshot({ path: framePath, type: 'png' })
  }

  await page.close()

  if (!which('ffmpeg')) {
    throw new Error('本机没有 ffmpeg（which ffmpeg 找不到），无法合成 mp4。帧序列已保留在 ' + framesDir)
  }

  fs.mkdirSync(OUT_DIR, { recursive: true })
  const mp4Path = path.join(OUT_DIR, `${card.name}.mp4`)
  const args = [
    '-y',
    '-r', String(FPS),
    '-i', path.join(framesDir, 'f%06d.png'),
    '-frames:v', String(totalFrames),
    '-pix_fmt', 'yuv420p',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '18',
    '-movflags', '+faststart',
    mp4Path,
  ]
  const r = spawnSync('ffmpeg', args, { stdio: 'pipe' })
  if (r.status !== 0) {
    throw new Error(`[${card.name}] ffmpeg 合成失败：\n` + r.stderr.toString().slice(-2000))
  }

  // 首/中/尾三帧另存一份，供渲染后人工核对（不入库，留在 out/ 下）
  const checkDir = path.join(OUT_DIR, `${card.name}-check`)
  fs.rmSync(checkDir, { recursive: true, force: true })
  fs.mkdirSync(checkDir, { recursive: true })
  const midIdx = Math.floor(totalFrames / 2)
  const lastIdx = totalFrames - 1
  for (const [label, idx] of [['first', 0], ['mid', midIdx], ['last', lastIdx]]) {
    const src = path.join(framesDir, `f${String(idx).padStart(6, '0')}.png`)
    fs.copyFileSync(src, path.join(checkDir, `${label}.png`))
  }

  return { mp4Path, frameCount: totalFrames, checkDir }
}

async function main() {
  const only = process.argv[2]
  const cards = only ? CARDS.filter((c) => c.name === only) : CARDS
  if (cards.length === 0) {
    throw new Error(`未知卡片名：${only}（可选：${CARDS.map((c) => c.name).join(', ')}）`)
  }

  if (!fs.existsSync(CHROME_PATH)) {
    throw new Error(`找不到 Chrome 可执行文件：${CHROME_PATH}（可用 CHROME_PATH 环境变量指定）`)
  }

  const framesRootDir = path.join(OUT_DIR, '.frames')
  fs.mkdirSync(framesRootDir, { recursive: true })

  console.log('起无头 Chrome...')
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: 'new',
    args: ['--force-color-profile=srgb', '--hide-scrollbars'],
  })

  const results = []
  try {
    for (const card of cards) {
      console.log(`渲染 ${card.name} ...`)
      const result = await renderCard(browser, card, framesRootDir)
      results.push({ name: card.name, ...result })
    }
  } finally {
    await browser.close()
  }

  // 帧序列只是中间产物，编码完成后清理，避免 out/ 里堆几千张 PNG
  fs.rmSync(framesRootDir, { recursive: true, force: true })

  console.log('')
  console.log('完成：')
  for (const r of results) {
    console.log(`  ${r.name}: ${r.mp4Path}（${r.frameCount} 帧，自查帧见 ${r.checkDir}）`)
  }
}

main().catch((err) => {
  console.error('[render.mjs] 失败:', err)
  process.exit(1)
})
