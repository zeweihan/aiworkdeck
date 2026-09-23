// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 系统剪贴板轮询（dev-board#869）。
//
// 现场：剪贴板里躺着一张图时，主进程空闲 CPU 7–11%。旧轮询每秒 readImage() 一次——
// macOS 上那是「TIFF 解码 → PNG 编码 → PNG 再解码」整一趟（2010x1466 实测 71ms/次），
// 再 toBitmap() 复制一整份 BGRA，只为取头尾 64 字节算指纹。成本随图片尺寸线性涨。
//
// 现在：macOS 用 NSPasteboard.changeCount 当廉价闸门——序号没变就说明还是上一次复制，
// 图片指纹不用重算；序号变了才读一次整图，指纹比对与推送逻辑与旧代码逐字一致
// （同一张图重复复制不重复推送、图文混合优先图、首 tick 只记指纹）。
// Electron 不暴露 changeCount，由一个常驻的 /usr/bin/osascript（JXA）每 0.5 秒读一次、
// 只在变化时写一行到 stdout；父进程没了它自己退出。
//
// 其余平台（Windows 的 GetClipboardSequenceNumber 同样没暴露）与 helper 起不来/中途退出时，
// getChangeCount() 返回 null，走廉价兜底：每 tick 先看 availableFormats()（便宜），
// 格式集与上一 tick 完全相同时整图只每 IMAGE_RECHECK_TICKS 个 tick 重读一次；格式集一变
// （复制了文本/文件/格式不同的新图）当 tick 立刻重读。文本与文件分支照旧每 tick。
// 取舍：「图换图且格式集不变」（例如连截两张图）的检测最多晚 IMAGE_RECHECK_TICKS 秒，
// 换来图躺在剪贴板里时整图解码的次数降到五分之一。
const { spawn } = require('child_process')

// 无变更序号时，格式集不变的情况下每几个 tick 才整图重读一次（tick 间隔 1 秒）
const IMAGE_RECHECK_TICKS = 5

// 父进程 pid 变了（被 launchd 收养）就退出，防止主进程崩溃后留下孤儿。
const DARWIN_CHANGE_COUNT_SCRIPT = `
ObjC.import('AppKit'); ObjC.import('unistd')
var pb = $.NSPasteboard.generalPasteboard
var out = $.NSFileHandle.fileHandleWithStandardOutput
var ppid = $.getppid(), last = -1
while ($.getppid() === ppid) {
  var c = pb.changeCount
  if (c !== last) { last = c; out.writeData($(String(c) + '\\n').dataUsingEncoding($.NSUTF8StringEncoding)) }
  delay(0.5)
}
`

/**
 * 起剪贴板变更序号的读取器。不支持的平台返回 null。
 * @returns {{ get: () => (number|null), stop: () => void } | null}
 */
function startChangeCounter({ platform = process.platform, spawnFn = spawn } = {}) {
  if (platform !== 'darwin') return null
  let child
  try {
    child = spawnFn('/usr/bin/osascript', ['-l', 'JavaScript', '-e', DARWIN_CHANGE_COUNT_SCRIPT], {
      stdio: ['ignore', 'pipe', 'ignore'],
    })
  } catch (e) {
    return null
  }
  let value = null
  let alive = true
  let buf = ''
  const die = () => { alive = false; value = null }
  child.on('error', die)
  child.on('exit', die)
  if (child.stdout) {
    child.stdout.on('data', (chunk) => {
      buf += String(chunk)
      const lines = buf.split('\n')
      buf = lines.pop()
      for (let i = lines.length - 1; i >= 0; i--) {
        const n = Number(lines[i].trim())
        if (lines[i].trim() && Number.isFinite(n)) { if (alive) value = n; break }
      }
    })
  }
  return {
    get: () => value,
    stop: () => {
      alive = false
      value = null
      try { child.kill() } catch (e) { /* ignore */ }
    },
  }
}

/**
 * 一次轮询的逻辑（不含定时器）。fingerprint 与 main.js 的 emitClipboard 共用，
 * 由调用方以 get/set 传入。
 */
function createClipboardPoller({ clipboard, platform = process.platform, getChangeCount = () => null, fingerprint, onImage, onFile, onText }) {
  // 上一次真正读过整图时的变更序号；序号未变 = 剪贴板还是那次复制，不必再读图
  let imageSeq = null
  // 兜底用：上一 tick 的格式集、上次整图读取后过了几个 tick、上次读到的是否是非空图
  let lastFormatsKey = null
  let ticksSinceImageRead = Infinity
  let lastImageNonEmpty = false

  function tick(priming) {
    const formats = clipboard.availableFormats()
    const formatsKey = formats.join('\n')
    const formatsChanged = formatsKey !== lastFormatsKey
    lastFormatsKey = formatsKey
    ticksSinceImageRead++

    const hasImage = formats.some(f => f.includes('image'))
    const hasText = formats.includes('text/plain')

    if (hasImage) {
      const seq = getChangeCount()
      if (seq != null && seq === imageSeq) return
      if (seq == null && lastImageNonEmpty && !formatsChanged && ticksSinceImageRead < IMAGE_RECHECK_TICKS) return
      const img = clipboard.readImage()
      ticksSinceImageRead = 0
      lastImageNonEmpty = !!(img && !img.isEmpty())
      if (lastImageNonEmpty) {
        imageSeq = seq
        // 用原始 bitmap 算指纹，仅当指纹变化（新图）时才做一次昂贵的 toDataURL。
        const bitmap = img.toBitmap()
        const size = img.getSize()
        const sample = bitmap.length > 64
          ? bitmap.subarray(0, 32).toString('hex') + bitmap.subarray(bitmap.length - 32).toString('hex')
          : bitmap.toString('hex')
        const fp = 'IMG_' + size.width + 'x' + size.height + '_' + bitmap.length + '_' + sample
        if (fp !== fingerprint.get()) {
          fingerprint.set(fp)
          if (priming) return
          onImage(img.toDataURL())
        }
        // 图文混合时优先图
        return
      }
    }

    // macOS 的文件复制（public.file-url）
    if (platform === 'darwin' && formats.includes('public.file-url')) {
      const filePath = clipboard.read('public.file-url')
      if (filePath) {
        let cleanPath = filePath
        try { cleanPath = decodeURIComponent(filePath.replace('file://', '')) } catch (e) { }

        const fp = 'FILE_' + cleanPath
        if (fp !== fingerprint.get()) {
          fingerprint.set(fp)
          if (priming) return
          onFile(cleanPath)
        }
        return
      }
    }

    if (hasText) {
      const t = clipboard.readText() || ''
      // trim 与 emitClipboard 保持一致，否则两处指纹对不上会反复推送
      const tt = String(t || '').trim()
      if (!tt) return
      const fp = 'TXT_' + tt
      if (fp !== fingerprint.get()) {
        fingerprint.set(fp)
        if (priming) return
        onText(tt)
      }
    }
  }

  return { tick }
}

module.exports = { createClipboardPoller, startChangeCounter, DARWIN_CHANGE_COUNT_SCRIPT, IMAGE_RECHECK_TICKS }
