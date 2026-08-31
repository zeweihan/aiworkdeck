#!/usr/bin/env node
/**
 * 渲染一键安装器（awd-oneclick-ui.nsh）的位图资产：
 *   desktop/build/win/oneclick-*.html → <out>/oneclick-*-{zh|en}-{100|125|150|200}.bmp
 * 与老的 render-win-installer-art.mjs（MUI 侧栏/页眉，产物入库）不同，本脚本产物
 * **不入库**（24 张 BMP 共几十 MB），在构建现场生成：
 *   - 桌面端：desktop-build.yml 的 Windows 打包步骤先跑本脚本再跑 electron-builder；
 *   - 插件端：office-addin/installer/build-installers.mjs 在 makensis 前调用。
 * 多 DPI 靠 --force-device-scale-factor 放大渲染，NSIS 侧按系统 DPI 选文件。
 * 依赖：Google Chrome（mac 自装 / GitHub runner 自带）、ImageMagick（同上）。
 *
 * 用法：node render-oneclick-art.mjs --product desktop|addin --out <目录> [--version x.y.z]
 *（--version 烧进进度小卡右侧的版本号，空则不显示）
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const desktopDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = path.join(desktopDir, 'build', 'win')

const args = process.argv.slice(2)
function argOf(name) {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : ''
}
const product = argOf('--product')
const outDir = argOf('--out')
const version = argOf('--version')
if (!['desktop', 'addin'].includes(product) || !outDir) {
  console.error('用法：node render-oneclick-art.mjs --product desktop|addin --out <目录>')
  process.exit(1)
}

function findChrome() {
  if (process.env.CHROME_PATH && fs.existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH
  const candidates = process.platform === 'darwin'
    ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    : process.platform === 'win32'
      ? ['C:/Program Files/Google/Chrome/Application/chrome.exe',
         'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe']
      : ['/usr/bin/google-chrome', '/usr/bin/chromium-browser', '/usr/bin/chromium']
  const hit = candidates.find(p => fs.existsSync(p))
  if (!hit) {
    console.error('[oneclick-art] 找不到 Chrome，可用 CHROME_PATH 环境变量指定')
    process.exit(1)
  }
  return hit
}
const chrome = findChrome()

// 尺寸契约与 awd-oneclick-ui.nsh 的 AWDUI_* 常量一致：
// 桌面端 hero 含「自定义安装」展开行（568），插件端没有（500）
const heroH = product === 'desktop' ? 568 : 500
const assets = [
  { html: 'oneclick-hero.html', w: 760, h: heroH, base: 'oneclick-hero' },
  { html: 'oneclick-mini-install.html', w: 360, h: 132, base: 'oneclick-mini-install' },
  { html: 'oneclick-mini-done.html', w: 360, h: 132, base: 'oneclick-mini-done' },
]
const langs = ['zh', 'en']
const scales = [[1, 100], [1.25, 125], [1.5, 150], [2, 200]]

fs.mkdirSync(outDir, { recursive: true })

// 新鲜检查：全部产物都比源（html + 本脚本 + 图标）新、且版本戳一致就跳过，别拖慢每次构建
const stampFile = path.join(outDir, '.stamp')
const stamp = `${product}|${version}`
const stampFresh = fs.existsSync(stampFile) && fs.readFileSync(stampFile, 'utf8') === stamp
const srcMtime = Math.max(
  ...assets.map(a => fs.statSync(path.join(srcDir, a.html)).mtimeMs),
  fs.statSync(fileURLToPath(import.meta.url)).mtimeMs,
  fs.statSync(path.join(desktopDir, 'build', 'icon.png')).mtimeMs,
)
const wanted = []
for (const a of assets) for (const lang of langs) for (const [, tag] of scales)
  wanted.push(`${a.base}-${lang}-${tag}.bmp`)
if (stampFresh && wanted.every(f => {
  const p = path.join(outDir, f)
  return fs.existsSync(p) && fs.statSync(p).mtimeMs > srcMtime
})) {
  console.log(`[oneclick-art] ${product}: ${wanted.length} 张位图均为最新，跳过渲染`)
  process.exit(0)
}

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-oneclick-art-'))
let n = 0
for (const a of assets) {
  for (const lang of langs) {
    for (const [factor, tag] of scales) {
      const png = path.join(tmpDir, `${a.base}-${lang}-${tag}.png`)
      const url = `file://${path.join(srcDir, a.html)}?lang=${lang}&product=${product}&ver=${encodeURIComponent(version)}`
      execFileSync(chrome, [
        '--headless=new', '--disable-gpu', '--hide-scrollbars',
        `--force-device-scale-factor=${factor}`, `--window-size=${a.w},${a.h}`,
        `--screenshot=${png}`, url,
      ], { stdio: 'ignore' })
      const bmp = path.join(outDir, `${a.base}-${lang}-${tag}.bmp`)
      execFileSync('magick', [png, '-alpha', 'off', '-type', 'TrueColor', `BMP3:${bmp}`], { stdio: 'inherit' })
      const buf = fs.readFileSync(bmp)
      if (buf.toString('ascii', 0, 2) !== 'BM' || buf.readUInt16LE(28) !== 24) {
        console.error(`[oneclick-art] ${path.basename(bmp)} 不是 24 位 BMP`)
        process.exit(1)
      }
      // 尺寸校验：宽高必须是基准 × 倍率，错位面会让热区全部对不上
      const w = buf.readInt32LE(18), h = buf.readInt32LE(22)
      const ew = Math.round(a.w * factor), eh = Math.round(a.h * factor)
      if (w !== ew || Math.abs(h) !== eh) {
        console.error(`[oneclick-art] ${path.basename(bmp)} 尺寸 ${w}x${Math.abs(h)}，期望 ${ew}x${eh}`)
        process.exit(1)
      }
      n++
    }
  }
}
fs.rmSync(tmpDir, { recursive: true, force: true })
fs.writeFileSync(stampFile, stamp)
console.log(`[oneclick-art] ${product}: 渲染 ${n} 张位图 → ${outDir}`)
