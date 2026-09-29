// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 回收站「彻底删除」送系统废纸篓（dev-board#1051）。
//
// 契约：渲染层把后端 GET .../permanent/disk-paths 报回的物理路径原样交给主进程；
// 主进程逐个 shell.trashItem，返回逐项成败。任一项失败 ok 就是假，渲染层据此不去清数据库行
// ——字节还在盘上时行先没了，回收站里就再也找不到它。本来就不在的路径算成功。
//
// electron 在裸 node 里 require 出来是个路径字符串，先往 require.cache 里塞一个假模块。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const electronId = require.resolve('electron')
require.cache[electronId] = {
  id: electronId, filename: electronId, loaded: true,
  exports: { ipcMain: { handle() {} }, dialog: {}, shell: {}, BrowserWindow: {}, ShareMenu: class {} },
}
const svc = require('../main/file-service')

const SRC = fs.readFileSync(path.join(__dirname, '../main/file-service.js'), 'utf8')
const PRELOAD = fs.readFileSync(path.join(__dirname, '../preload/preload.js'), 'utf8')

function fakeShell(failOn = new Set()) {
  const trashed = []
  return {
    trashed,
    async trashItem(p) {
      if (failOn.has(p)) throw new Error('boom')
      trashed.push(p)
    },
  }
}

function tmpTree() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-trash-'))
  fs.writeFileSync(path.join(dir, 'a.docx'), 'a')
  fs.mkdirSync(path.join(dir, 'A'))
  return dir
}

test('preload 暴露 fs.trashItems，走 fs:trashItems 通道', () => {
  assert.match(PRELOAD, /trashItems:\s*\(paths\)\s*=>\s*ipcRenderer\.invoke\('fs:trashItems',\s*\{\s*paths\s*\}\)/)
})

test('主进程登记 fs:trashItems 并交给 trashItems', () => {
  const m = SRC.match(/ipcMain\.handle\('fs:trashItems'[\s\S]*?\n\s{4}\}\);/)
  assert.ok(m, '缺少 fs:trashItems handler')
  assert.match(m[0], /trashItems\(paths\)/)
})

test('逐个送进废纸篓，全部成功才 ok', async () => {
  const dir = tmpTree()
  const sh = fakeShell()
  const a = path.join(dir, 'a.docx')
  const A = path.join(dir, 'A')
  const r = await svc.trashItems([a, A], { shell: sh })
  assert.equal(r.ok, true)
  assert.deepEqual(sh.trashed, [a, A])
  assert.deepEqual(r.results.map((x) => x.ok), [true, true])
})

test('某一项失败：整体 ok 为假，其余照常送走并逐项回报', async () => {
  const dir = tmpTree()
  const a = path.join(dir, 'a.docx')
  const A = path.join(dir, 'A')
  const sh = fakeShell(new Set([a]))
  const r = await svc.trashItems([a, A], { shell: sh })
  assert.equal(r.ok, false)
  assert.equal(r.results[0].reason, 'trash-failed')
  assert.equal(r.results[1].ok, true)
})

test('本来就不在的路径算成功（已达成），不去调 trashItem', async () => {
  const dir = tmpTree()
  const sh = fakeShell()
  const gone = path.join(dir, 'gone.docx')
  const r = await svc.trashItems([gone], { shell: sh })
  assert.equal(r.ok, true)
  assert.equal(r.results[0].missing, true)
  assert.deepEqual(sh.trashed, [])
})

test('拒绝相对路径、带 .. 的路径与非字符串', async () => {
  const dir = tmpTree()
  const sh = fakeShell()
  const r = await svc.trashItems(['a.docx', path.join(dir, 'A') + '/../a.docx', 42, ''], { shell: sh })
  assert.equal(r.ok, false)
  assert.deepEqual(r.results.map((x) => x.reason), ['not-absolute', 'not-normalized', 'not-absolute', 'not-absolute'])
  assert.deepEqual(sh.trashed, [])
})

test('拒绝文件系统根、主目录本身与主目录的祖先', async () => {
  const sh = fakeShell()
  const home = path.join(os.tmpdir(), 'awd-home', 'u')
  const r = await svc.trashItems(['/', home, path.dirname(home)], { shell: sh, homedir: home })
  assert.deepEqual(r.results.map((x) => x.reason), ['protected', 'protected', 'protected'])
  assert.deepEqual(sh.trashed, [])
})

test('参数不是数组：明确失败，不抛', async () => {
  const r = await svc.trashItems(undefined, { shell: fakeShell() })
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'bad-paths')
})
