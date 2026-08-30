#!/usr/bin/env node
/**
 * 渲染 Windows 安装器美术资产：build/win/*.html → PNG（headless Chrome）→ 24 位 BMP3（ImageMagick）。
 * NSIS/MUI2 只认无 alpha 的经典 BMP，sips 只能输出 32 位，所以走 magick。
 * 仅维护者改美术时在 Mac 上手动运行，产物（.bmp）入库；CI 与用户构建都不需要 Chrome/ImageMagick。
 * 用法：node scripts/render-win-installer-art.mjs
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const desktopDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const winDir = path.join(desktopDir, 'build', 'win')
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

const jobs = [
  { html: 'installer-sidebar.html', w: 164, h: 314, out: 'installerSidebar.bmp' },
  { html: 'installer-header.html', w: 150, h: 57, out: 'installerHeader.bmp' },
]

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-installer-art-'))
for (const job of jobs) {
  const png = path.join(tmpDir, job.out.replace(/\.bmp$/, '.png'))
  execFileSync(chrome, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars',
    '--force-device-scale-factor=1', `--window-size=${job.w},${job.h}`,
    `--screenshot=${png}`, `file://${path.join(winDir, job.html)}`,
  ], { stdio: 'ignore' })
  const bmp = path.join(winDir, job.out)
  execFileSync('magick', [png, '-alpha', 'off', '-type', 'TrueColor', `BMP3:${bmp}`], { stdio: 'inherit' })
  // 校验：BM 头 + 24 位色深（偏移 28 的 biBitCount），不合格宁可失败也不入库
  const buf = fs.readFileSync(bmp)
  if (buf.toString('ascii', 0, 2) !== 'BM' || buf.readUInt16LE(28) !== 24) {
    console.error(`[installer-art] ${job.out} 不是 24 位 BMP，请检查 ImageMagick 输出`)
    process.exit(1)
  }
  console.log(`[installer-art] ${job.out} ${job.w}x${job.h} 24-bit ok`)
}
fs.rmSync(tmpDir, { recursive: true, force: true })
