// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-34：导出 PDF 的存盘对话框默认目录 + 成功提示时机。
//
// 纯逻辑在 main/export-download.js 真跑；main.js 起手就拉一堆服务，node 直接 require
// 不进来，接线部分与 recovery-download.test.js 同口径做源码级断言。
//
// J1 复核否决第一版的两处：
//  ① 渲染层用 `/` 正则截目录，Windows 的 `C:\案件\合同.docx` 没有 `/`，截出来是整条文件
//     路径 → 对话框默认路径变成「合同.docx\合同.pdf」。现在主进程拿源文件路径用平台
//     path.dirname 取目录，下面用 path.win32 真跑一遍 Windows 路径；
//  ② 成功提示在对话框刚弹出时就弹，存完已经看不到了。现在按下载项 done 回报。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')
const { EventEmitter } = require('node:events')

const { exportDefaultPath, reportDownloadDone } = require('../main/export-download')

const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const CODE = SRC.replace(/(^|\s)\/\*[\s\S]*?\*\//g, '$1').replace(/^\s*\/\/.*$/gm, '')
const PRELOAD = fs.readFileSync(path.join(__dirname, '../preload/preload.js'), 'utf8')

test('Windows 反斜杠路径：默认路径落在源文件所在目录，不是把文件路径当目录', () => {
  const got = exportDefaultPath({ filename: '合同.pdf', sourceFilePath: 'C:\\Users\\lawyer\\案件\\合同.docx' }, path.win32)
  assert.strictEqual(got, 'C:\\Users\\lawyer\\案件\\合同.pdf')
  // UNC 共享盘
  assert.strictEqual(
    exportDefaultPath({ filename: 'a.pdf', sourceFilePath: '\\\\nas\\share\\proj\\a.docx' }, path.win32),
    '\\\\nas\\share\\proj\\a.pdf')
})

test('POSIX 路径：同样落在源文件所在目录', () => {
  assert.strictEqual(
    exportDefaultPath({ filename: '合同.pdf', sourceFilePath: '/Users/lawyer/案件/合同.docx' }, path.posix),
    '/Users/lawyer/案件/合同.pdf')
})

test('优先级：复敏映射固定目录 > 源文件目录 > 裸文件名（系统默认目录）', () => {
  assert.strictEqual(exportDefaultPath({ filename: 'x.awd-recovery', recoveryPath: '/D/R/x.awd-recovery', sourceFilePath: '/p/a.docx' }, path.posix),
    '/D/R/x.awd-recovery', '复敏文件是密钥材料，固定目录不许被导出提示顶掉')
  assert.strictEqual(exportDefaultPath({ filename: 'a.pdf' }, path.posix), 'a.pdf')
  assert.strictEqual(exportDefaultPath({ filename: 'a.pdf', sourceFilePath: 'relative/a.docx' }, path.posix), 'a.pdf',
    '相对路径不可信，退回原行为')
  assert.strictEqual(exportDefaultPath({ filename: 'a.pdf', sourceFilePath: { evil: 1 } }, path.posix), 'a.pdf')
})

test('下载结束回报：completed / cancelled 都回给发起的页面，带文件名与存盘路径', () => {
  for (const state of ['completed', 'cancelled', 'interrupted']) {
    const item = new EventEmitter()
    item.getFilename = () => '合同.pdf'
    item.getSavePath = () => (state === 'completed' ? '/Users/lawyer/案件/合同.pdf' : '')
    const sent = []
    const wc = { isDestroyed: () => false, send: (ch, data) => sent.push({ ch, data }) }
    reportDownloadDone(item, wc)
    item.emit('done', {}, state)
    assert.deepStrictEqual(sent, [{ ch: 'checkba:download-done', data: {
      filename: '合同.pdf', savePath: item.getSavePath(), state } }])
  }
})

test('页面已销毁：不回报、不抛', () => {
  const item = new EventEmitter()
  item.getFilename = () => 'a.pdf'
  item.getSavePath = () => ''
  const wc = { isDestroyed: () => true, send() { throw new Error('不该发') } }
  reportDownloadDone(item, wc)
  assert.doesNotThrow(() => item.emit('done', {}, 'completed'))
})

test('接线：will-download 用 exportDefaultPath 算默认路径并挂 done 回报；源路径一次性消费', () => {
  const handler = CODE.match(/session\.on\('will-download'[\s\S]*?\n {2}\}\)/)
  assert.ok(handler, '截不到 will-download 监听器')
  assert.match(handler[0], /const sourceFilePath = nextExportSource\s*\n\s*nextExportSource = null/,
    '读取之后必须马上清空——否则下一次跟导出无关的下载会被上一次的目录带偏')
  assert.match(handler[0], /exportDefaultPath\(\{ filename: item\.getFilename\(\), recoveryPath, sourceFilePath \}\)/)
  assert.match(handler[0], /reportDownloadDone\(item, webContents\)/)
  assert.match(CODE, /ipcMain\.handle\('fs:setNextExportSource'/)
  assert.doesNotMatch(CODE, /nextDownloadDir/, '旧的「渲染层截目录」接口要拆干净')
  assert.match(PRELOAD, /setNextExportSource: \(filePath\) => ipcRenderer\.invoke\('fs:setNextExportSource', \{ filePath \}\)/)
  assert.match(PRELOAD, /ipcRenderer\.on\('checkba:download-done', listener\)/)
})
