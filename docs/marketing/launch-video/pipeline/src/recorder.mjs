// CDP Page.startScreencast 收帧，落盘 PNG 序列 + 每帧真实时长；
// 结束时用 ffmpeg 的 concat demuxer（每帧带 duration）合成恒定 30fps 的 mp4——
// screencast 到帧的间隔并不均匀，直接 `ffmpeg -r 30 -i frame_%d.png` 会让整段
// 播放速度失真，必须按真实墙钟时间撑开/压缩每一帧。

import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { FPS } from './config.mjs'

function which(bin) {
  const r = spawnSync('which', [bin])
  return r.status === 0
}

/**
 * @param {import('puppeteer-core').Page} page
 * @param {string} outDir 落帧与产物的目录（会新建）
 * @returns {Promise<{stop: () => Promise<{mp4Path:string, frameCount:number}>}>}
 */
export async function startRecording(page, outDir) {
  fs.mkdirSync(outDir, { recursive: true })
  const framesDir = path.join(outDir, 'frames')
  fs.mkdirSync(framesDir, { recursive: true })

  const cdp = page.__awdCdp || await page.target().createCDPSession()
  const frames = [] // { file, ts }
  let stopped = false

  const onFrame = async (frame) => {
    if (stopped) return
    const idx = frames.length
    const file = path.join(framesDir, `f${String(idx).padStart(6, '0')}.png`)
    fs.writeFileSync(file, Buffer.from(frame.data, 'base64'))
    frames.push({ file, ts: frame.metadata.timestamp || Date.now() / 1000 })
    try {
      await cdp.send('Page.screencastFrameAck', { sessionId: frame.sessionId })
    } catch (e) { /* 会话可能已经在收尾，忽略 */ }
  }
  cdp.on('Page.screencastFrame', onFrame)

  await cdp.send('Page.startScreencast', {
    format: 'png',
    maxWidth: 1920,
    maxHeight: 1080,
    everyNthFrame: 1,
  })

  const stopWallClock = { at: null }

  const stop = async () => {
    stopWallClock.at = Date.now() / 1000
    await cdp.send('Page.stopScreencast').catch(() => { /* 忽略 */ })
    // 让最后几帧的 ack 有机会落盘
    await new Promise((r) => setTimeout(r, 300))
    stopped = true
    cdp.off('Page.screencastFrame', onFrame)

    if (frames.length === 0) {
      throw new Error('录屏没有收到任何帧——CDP 会话可能已断开')
    }

    const listPath = path.join(outDir, 'concat.txt')
    const lines = ['ffconcat version 1.0']
    for (let i = 0; i < frames.length; i++) {
      const cur = frames[i]
      const next = frames[i + 1]
      const nextTs = next ? next.ts : stopWallClock.at
      const duration = Math.max(1 / FPS, nextTs - cur.ts)
      lines.push(`file '${path.relative(outDir, cur.file)}'`)
      lines.push(`duration ${duration.toFixed(4)}`)
    }
    // concat demuxer 要求最后一帧再重复一行 file（duration 只在两帧之间生效）
    lines.push(`file '${path.relative(outDir, frames[frames.length - 1].file)}'`)
    fs.writeFileSync(listPath, lines.join('\n') + '\n')

    if (!which('ffmpeg')) {
      throw new Error('本机没有 ffmpeg（which ffmpeg 找不到），无法合成 mp4。帧序列已保留在 ' + framesDir)
    }

    const mp4Path = path.join(outDir, 'sample.mp4')
    const args = [
      '-y',
      '-f', 'concat', '-safe', '0', '-i', listPath,
      // concat demuxer 按每帧的 duration 摆好了输入时间轴（可变间隔）；-fps_mode cfr
      // 把它重采样成恒定 30fps（复制/丢帧），跟 -r 30 一起用才不矛盾
      // （旧写法 -vsync vfr + -r 30 在 ffmpeg 7.x 上会直接报「contradictory」拒绝开工）。
      '-fps_mode', 'cfr',
      '-r', String(FPS),
      '-pix_fmt', 'yuv420p',
      '-c:v', 'libx264', '-preset', 'medium', '-crf', '18',
      mp4Path,
    ]
    const r = spawnSync('ffmpeg', args, { cwd: outDir, stdio: 'pipe' })
    if (r.status !== 0) {
      throw new Error('ffmpeg 合成失败：\n' + r.stderr.toString().slice(-2000))
    }

    return { mp4Path, frameCount: frames.length }
  }

  return { stop }
}
