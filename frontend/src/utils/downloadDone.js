// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 等一次下载真正结束（v0.49.0 BUG-34）。
//
// 桌面端的 a[download] 会弹「另存为」，用户可能选很久、也可能取消。成功提示若在
// click() 之后立刻弹，对话框还开着它就消失了，取消了也照样说「已导出」。主进程在
// 下载项 done 时回报 checkba:download-done（desktop/main/export-download.js），这里按
// 文件名认领自己那一次。浏览器态没有这条回报（host.fs.onDownloadDone 缺席），返回 null，
// 调用方照旧立刻提示——浏览器自己的下载栏就是反馈。

/**
 * **必须在触发下载之前调用**（先订阅再 click，否则快的时候会错过回报）。
 * @param {any} fs host.fs
 * @param {string} filename a[download] 用的文件名（will-download 的 getFilename() 就是它）
 * @param {{ timeoutMs?: number }} [opts] 超时后当作没等到（resolve null）并退订
 * @returns {Promise<{state: string, savePath: string, filename: string}|null>|null}
 */
export function watchDownloadDone(fs, filename, { timeoutMs = 30 * 60 * 1000 } = {}) {
  if (!fs || typeof fs.onDownloadDone !== 'function') return null
  return new Promise((resolve) => {
    let off = null, timer = null
    const finish = (v) => {
      if (timer) clearTimeout(timer)
      if (typeof off === 'function') { try { off() } catch (e) { /* ignore */ } }
      off = null
      resolve(v)
    }
    off = fs.onDownloadDone((d) => {
      if (!d || d.filename !== filename) return // 别的下载，不是这一次
      finish({ state: String(d.state || ''), savePath: String(d.savePath || ''), filename: d.filename })
    })
    timer = setTimeout(() => finish(null), timeoutMs)
  })
}

/** 存盘路径的文件名部分（提示用；Windows 反斜杠与 POSIX 斜杠都认）。 */
export function displayName(savePath, fallback) {
  const s = String(savePath || '')
  const base = s.split(/[\\/]/).pop()
  return base || fallback || ''
}
