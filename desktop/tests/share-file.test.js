// 文件「发送…」（dev-board#382）。
//
// 契约：渲染层只传一条由后端解析出的物理路径；主进程先确认文件存在，再按平台分流——
// macOS 交给系统分享面板（微信 Mac 版的分享扩展在里面），Windows 退化为剪贴板 +
// 拉起微信，其余平台明确返回 unsupported，绝不静默吞掉。
//
// electron 在裸 node 里 require 出来是个路径字符串，先往 require.cache 里塞一个
// 假模块，ShareMenu 的调用参数才能被断言到。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const calls = []
class FakeShareMenu {
  constructor(item) { calls.push({ ctor: item }) }
  popup(opts) { calls.push({ popup: opts }) }
}
const electronId = require.resolve('electron')
require.cache[electronId] = {
  id: electronId, filename: electronId, loaded: true,
  exports: { ipcMain: { handle() {} }, dialog: {}, shell: {}, BrowserWindow: {}, ShareMenu: FakeShareMenu },
}
const svc = require('../main/file-service')

const SRC = fs.readFileSync(path.join(__dirname, '../main/file-service.js'), 'utf8')
const PRELOAD = fs.readFileSync(path.join(__dirname, '../preload/preload.js'), 'utf8')

test('preload 暴露 fs.shareFile，走 fs:shareFile 通道', () => {
  assert.match(PRELOAD, /shareFile:\s*\(path\)\s*=>\s*ipcRenderer\.invoke\('fs:shareFile',\s*\{\s*path\s*\}\)/)
})

test('主进程登记 fs:shareFile，且先校验路径存在再分流', () => {
  const m = SRC.match(/ipcMain\.handle\('fs:shareFile'[\s\S]*?\n\s{4}\}\);/)
  assert.ok(m, '缺少 fs:shareFile handler')
  assert.match(m[0], /typeof filePath !== 'string'/, '必须拒绝非字符串路径')
  assert.match(m[0], /fs\.existsSync\(filePath\)/, '不存在的路径不能进分享面板')
  assert.match(m[0], /shareFile\(process\.platform/, '按当前平台分流')
})

test('macOS：用 ShareMenu 带上文件路径弹系统分享面板，挂在发起窗口上', () => {
  calls.length = 0
  const win = { id: 1 }
  const r = svc.shareFile('darwin', '/tmp/合同.docx', win)
  assert.deepEqual(r, { ok: true, mode: 'share-sheet' })
  assert.deepEqual(calls[0], { ctor: { filePaths: ['/tmp/合同.docx'] } })
  assert.deepEqual(calls[1], { popup: { window: win } })
})

test('macOS：没有窗口对象也能弹（popup 不传 window）', () => {
  calls.length = 0
  svc.shareFile('darwin', '/tmp/a.docx', null)
  assert.deepEqual(calls[1], { popup: {} })
})

test('其他平台明确返回 unsupported，不抛也不静默', () => {
  assert.deepEqual(svc.shareFile('linux', '/tmp/a.docx', null), { ok: false, reason: 'unsupported' })
})

test('Windows：PowerShell 单引号转义——路径里的单引号翻倍，其余原样', () => {
  assert.equal(svc.psQuote("C:\\Users\\O'Brien\\合同.docx"), "'C:\\Users\\O''Brien\\合同.docx'")
  assert.equal(svc.windowsClipboardCommand('C:\\a b\\c.docx'), "Set-Clipboard -LiteralPath 'C:\\a b\\c.docx'")
})

test('Windows：微信 4.0（Weixin）优先于 3.x（WeChat）', () => {
  const keys = svc.WECHAT_REGISTRY_CANDIDATES.map((c) => c.exe)
  assert.deepEqual(keys, ['Weixin.exe', 'WeChat.exe'])
})
