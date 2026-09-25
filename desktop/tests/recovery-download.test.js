// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 复敏映射（.awd-recovery）的「另存为」默认位置（真机走查 C3）。
//
// 病灶：will-download 的 setSaveDialogOptions 只给了裸文件名，于是对话框开在系统
// 上次用过的目录——真机上复敏文件落进了「7-常用图片」。它是解密脱敏副本的密钥材料，
// 必须与副本分开、放在一个固定的、用户找得到的地方。
//
// main.js 起手就 new BrowserWindow / 拉服务，node 直接 require 不进来，所以纯逻辑
// 抽到 main/recovery-download.js 真跑，接线部分与 native-theme-light.test.js 同口径
// 做源码级断言。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const { recoveryDefaultPath, isRecoveryFile, DIR_NAME } = require('../main/recovery-download')

const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const CODE = SRC.replace(/(^|\s)\/\*[\s\S]*?\*\//g, '$1').replace(/^\s*\/\/.*$/gm, '')

function env(over = {}) {
  const made = []
  return {
    made,
    options: {
      documentsDir: '/Users/tester/Documents',
      language: 'zh-CN',
      mkdir: (dir) => { made.push(dir) },
      ...over,
    },
  }
}

test('复敏文件默认落在 ~/Documents 下的固定目录，文件名原样保留', () => {
  const e = env()
  const target = recoveryDefaultPath('租赁合同-复敏-1789.awd-recovery', e.options)
  assert.strictEqual(target,
    path.join('/Users/tester/Documents', DIR_NAME.zh, '租赁合同-复敏-1789.awd-recovery'))
  assert.deepStrictEqual(e.made, [path.join('/Users/tester/Documents', DIR_NAME.zh)],
    '目录不存在要先建出来，否则对话框还是开在别处')
})

test('英文界面用英文目录名', () => {
  const e = env({ language: 'en-US' })
  const target = recoveryDefaultPath('lease-recovery-1789.awd-recovery', e.options)
  assert.strictEqual(path.basename(path.dirname(target)), DIR_NAME.en)
})

test('目录名不含 emoji，也不是项目目录里的相对路径', () => {
  const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/u
  for (const name of Object.values(DIR_NAME)) {
    assert.ok(!emoji.test(name), name + ' 含 emoji')
  }
  const target = recoveryDefaultPath('a.awd-recovery', env().options)
  assert.ok(path.isAbsolute(target), '必须是绝对路径，裸文件名就是这条 bug 的病灶')
})

test('其它下载一律不碰：仍然退回系统默认目录', () => {
  const e = env()
  for (const name of ['report.docx', 'screenshot.png', 'notes.awd-recovery.txt', '']) {
    assert.strictEqual(recoveryDefaultPath(name, e.options), null, name)
  }
  assert.deepStrictEqual(e.made, [], '不该为无关下载建目录')
  assert.ok(isRecoveryFile('X.AWD-RECOVERY'), '扩展名比较要忽略大小写')
})

test('建目录失败不打断下载，退回裸文件名', () => {
  const target = recoveryDefaultPath('a.awd-recovery', env({
    mkdir: () => { throw new Error('EACCES') },
  }).options)
  assert.strictEqual(target, null)
})

test('拿不到 Documents 目录时也不炸（app.getPath 在 ready 之前会抛）', () => {
  assert.strictEqual(recoveryDefaultPath('a.awd-recovery', env({ documentsDir: '' }).options), null)
})

test('will-download 真的用上了这个默认路径', () => {
  const handler = CODE.match(/session\.on\('will-download'[\s\S]*?\n {2}\}\)/)
  assert.ok(handler, "截不到 will-download 监听器")
  assert.match(handler[0], /recovery-download/, '没接上就仍然开在上次用过的目录')
  // BUG-34：默认路径改由 export-download.js 的 exportDefaultPath 统一算（复敏固定目录
  // 优先级最高，见 pdf-export-download-dir.test.js 的优先级用例）。
  assert.match(handler[0], /exportDefaultPath\(\{ filename: item\.getFilename\(\), recoveryPath, sourceFilePath \}\)/,
    '复敏文件走固定目录，其它下载看导出源目录，都没有才退回原行为')
  assert.match(handler[0], /app\.getPath\('documents'\)/)
})
