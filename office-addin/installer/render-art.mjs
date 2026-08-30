#!/usr/bin/env node
/**
 * 渲染 Office 插件安装器美术资产（维护者改美术时手动跑，产物入库；构建/CI 不需要本脚本）：
 *   art/installer-sidebar.html → win/installerSidebar.bmp   （164x314，24 位 BMP3）
 *   art/installer-header.html  → win/installerHeader.bmp    （150x57，24 位 BMP3）
 *   art/dmg-background.html    → mac/dmg-background{,@2x}.png（660x420，hidpi TIFF 由构建时 tiffutil 合成）
 *   desktop/build/icon.png     → win/installer.ico          （16/32/48/256 PNG 条目）
 * 依赖：Google Chrome、ImageMagick（brew install imagemagick）、sips。
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const installerDir = path.dirname(fileURLToPath(import.meta.url))
const repoDir = path.resolve(installerDir, '..', '..')
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-addin-art-'))

function shoot(html, w, h, scale, outPng) {
  execFileSync(chrome, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars',
    `--force-device-scale-factor=${scale}`, `--window-size=${w},${h}`,
    `--screenshot=${outPng}`, `file://${path.join(installerDir, 'art', html)}`,
  ], { stdio: 'ignore' })
}

// 1) Windows MUI2 位图（必须 24 位无 alpha，sips 做不到，走 magick）
for (const job of [
  { html: 'installer-sidebar.html', w: 164, h: 314, out: 'installerSidebar.bmp' },
  { html: 'installer-header.html', w: 150, h: 57, out: 'installerHeader.bmp' },
]) {
  const png = path.join(tmpDir, job.out + '.png')
  shoot(job.html, job.w, job.h, 1, png)
  const bmp = path.join(installerDir, 'win', job.out)
  execFileSync('magick', [png, '-alpha', 'off', '-type', 'TrueColor', `BMP3:${bmp}`], { stdio: 'inherit' })
  const buf = fs.readFileSync(bmp)
  if (buf.toString('ascii', 0, 2) !== 'BM' || buf.readUInt16LE(28) !== 24) {
    console.error(`[render-art] ${job.out} 不是 24 位 BMP`)
    process.exit(1)
  }
  console.log(`[render-art] ${job.out} ok`)
}

// 2) DMG 背景 1x/2x
shoot('dmg-background.html', 660, 420, 1, path.join(installerDir, 'mac', 'dmg-background.png'))
shoot('dmg-background.html', 660, 420, 2, path.join(installerDir, 'mac', 'dmg-background@2x.png'))
console.log('[render-art] dmg-background{,@2x}.png ok')

// 3) Windows 安装器 .ico（PNG 条目，Win Vista+ 均支持；NSIS 3 可直接嵌入）
const icoSizes = [16, 32, 48, 256]
const pngs = icoSizes.map(size => {
  const p = path.join(tmpDir, `ico-${size}.png`)
  execFileSync('sips', ['-z', String(size), String(size),
    path.join(repoDir, 'desktop', 'build', 'icon.png'), '--out', p], { stdio: 'ignore' })
  return fs.readFileSync(p)
})
const header = Buffer.alloc(6)
header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(icoSizes.length, 4)
const entries = []
let offset = 6 + 16 * icoSizes.length
icoSizes.forEach((size, i) => {
  const e = Buffer.alloc(16)
  e.writeUInt8(size === 256 ? 0 : size, 0)
  e.writeUInt8(size === 256 ? 0 : size, 1)
  e.writeUInt16LE(1, 4)   // planes
  e.writeUInt16LE(32, 6)  // bpp
  e.writeUInt32LE(pngs[i].length, 8)
  e.writeUInt32LE(offset, 12)
  offset += pngs[i].length
  entries.push(e)
})
fs.writeFileSync(path.join(installerDir, 'win', 'installer.ico'), Buffer.concat([header, ...entries, ...pngs]))
console.log('[render-art] installer.ico ok')

fs.rmSync(tmpDir, { recursive: true, force: true })
