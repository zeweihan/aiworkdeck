// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 导出 PDF 的「另存为」默认目录与完成回报（v0.49.0 BUG-34）。
//
// 病灶一：will-download 只给了裸文件名，对话框开在系统「上次用过的目录」，而不是
// 这份文档所在的项目目录。渲染层导出前经 fs:setNextExportSource 报一次**源文件的
// 绝对路径**，这里用平台自己的 path.dirname 取目录——第一版在渲染层用 `/` 正则
// 截目录，Windows 的 `C:\\案件\\合同.docx` 一个 `/` 都没有，截出来的是整条文件路径。
//
// 病灶二：成功提示在 a[download].click() 之后立刻弹，那一刻存盘对话框才刚打开，
// 1.5 秒的 toast 在用户选目录时就消失了，取消了也照样说「已导出」。现在按下载项的
// done 事件回报渲染层（checkba:download-done），渲染层只在 completed 时提示。
const path = require('path')

/**
 * 另存为的默认路径。优先级：复敏映射固定目录 > 导出源文件所在目录 > 裸文件名（系统默认目录）。
 * @param {{ filename: string, recoveryPath?: string|null, sourceFilePath?: string|null }} opts
 * @param {typeof path} [p] 路径实现（测试里传 path.win32 验 Windows 路径）
 */
function exportDefaultPath({ filename, recoveryPath, sourceFilePath } = {}, p = path) {
  if (recoveryPath) return recoveryPath
  const name = String(filename || '')
  if (typeof sourceFilePath === 'string' && sourceFilePath && p.isAbsolute(sourceFilePath)) {
    const dir = p.dirname(sourceFilePath)
    if (dir && dir !== sourceFilePath) return p.join(dir, p.basename(name))
  }
  return name
}

/** 下载项结束时回报给发起下载的那个页面。state: 'completed' | 'cancelled' | 'interrupted'。 */
function reportDownloadDone(item, webContents) {
  if (!item || typeof item.once !== 'function') return
  item.once('done', (_e, state) => {
    try {
      if (!webContents || (typeof webContents.isDestroyed === 'function' && webContents.isDestroyed())) return
      webContents.send('checkba:download-done', {
        filename: item.getFilename(),
        savePath: typeof item.getSavePath === 'function' ? item.getSavePath() : '',
        state,
      })
    } catch (e) { /* 窗口已关：没人等这条回报 */ }
  })
}

module.exports = { exportDefaultPath, reportDownloadDone }
