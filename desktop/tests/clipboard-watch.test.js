// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 剪贴板轮询不许每秒整图解码（dev-board#869）。
//
// 旧轮询在剪贴板是图片时每 tick 都 readImage()+toBitmap()，只为算头尾 64 字节的指纹；
// macOS 上 readImage 是「TIFF 解码 → PNG 编码 → PNG 解码」整一趟，主进程空闲 CPU 7–11%。
// 这里用计数的假 clipboard 钉死：变更序号不变时一次整图都不读；序号变了才读，
// 换图照样推送、同一张图不重复推送、文本路径不受影响。
const test = require('node:test')
const assert = require('node:assert')
const { EventEmitter } = require('node:events')
const { createClipboardPoller, startChangeCounter } = require('../main/clipboard-watch')

function fakeImage(tag, counts) {
  const bytes = Buffer.alloc(4000 * 4, 0)
  bytes.write(tag, 0)
  bytes.write(tag, bytes.length - tag.length)
  return {
    isEmpty: () => false,
    getSize: () => ({ width: 100, height: 10 }),
    toBitmap: () => { counts.toBitmap++; return Buffer.from(bytes) },
    toDataURL: () => 'data:image/png;base64,' + tag,
  }
}

function setup({ withCounter = true } = {}) {
  const counts = { readImage: 0, toBitmap: 0 }
  const board = { formats: ['image/png'], image: fakeImage('A', counts), text: '', seq: 1 }
  const clipboard = {
    availableFormats: () => board.formats.slice(),
    readImage: () => { counts.readImage++; return board.image },
    readText: () => board.text,
    read: () => '',
  }
  let fp = ''
  const pushed = []
  const poller = createClipboardPoller({
    clipboard,
    platform: 'darwin',
    getChangeCount: () => (withCounter ? board.seq : null),
    fingerprint: { get: () => fp, set: (v) => { fp = v } },
    onImage: (d) => pushed.push({ type: 'IMAGE', d }),
    onFile: (p) => pushed.push({ type: 'FILE', p }),
    onText: (t) => pushed.push({ type: 'TEXT', t }),
  })
  let primed = false
  const tick = () => { const priming = !primed; primed = true; poller.tick(priming) }
  return { counts, board, pushed, tick }
}

test('同一张图连续轮询：只在首 tick 读一次整图，之后不再 readImage/toBitmap', () => {
  const { counts, pushed, tick } = setup()
  for (let i = 0; i < 10; i++) tick()
  assert.strictEqual(counts.readImage, 1)
  assert.strictEqual(counts.toBitmap, 1)
  assert.deepStrictEqual(pushed, [], '启动前就在剪贴板里的图不算新复制')
})

test('换图后（序号前进）检测到并只推送一次', () => {
  const { counts, board, pushed, tick } = setup()
  tick()
  tick()
  board.image = fakeImage('B', counts)
  board.seq = 2
  for (let i = 0; i < 5; i++) tick()
  assert.deepStrictEqual(pushed.map(p => p.d), ['data:image/png;base64,B'])
  assert.strictEqual(counts.readImage, 2, '序号变化那一次读图，其余 tick 不读')
})

test('同一张图被重新复制（序号前进、内容不变）：读一次比指纹，不重复推送', () => {
  const { counts, board, pushed, tick } = setup()
  tick()
  board.seq = 2
  tick()
  tick()
  assert.deepStrictEqual(pushed, [])
  assert.strictEqual(counts.readImage, 2)
})

test('图 → 文本 → 同一张图：文本照常推送，图重新出现时再推一次（与旧行为一致）', () => {
  const { board, pushed, tick } = setup()
  tick()
  board.formats = ['text/plain']
  board.text = '  甲方  '
  board.seq = 2
  tick()
  tick()
  board.formats = ['image/png']
  board.seq = 3
  tick()
  assert.deepStrictEqual(pushed.map(p => p.type + ':' + (p.t || p.d)), ['TEXT:甲方', 'IMAGE:data:image/png;base64,A'])
})

test('无变更序号兜底：同一张图连续 10 tick 只整图读 2 次（首 tick + 第 6 tick）', () => {
  const { counts, pushed, tick } = setup({ withCounter: false })
  const readAt = []
  for (let i = 1; i <= 10; i++) {
    const before = counts.readImage
    tick()
    if (counts.readImage > before) readAt.push(i)
  }
  assert.deepStrictEqual(readAt, [1, 6])
  assert.strictEqual(counts.toBitmap, 2)
  assert.deepStrictEqual(pushed, [])
})

test('无变更序号兜底：formats 变化的 tick 立刻重读（新图与图→文本→图都不等 5 秒）', () => {
  const { counts, board, pushed, tick } = setup({ withCounter: false })
  tick()
  // 新图带来不同的格式集：当 tick 就读、就推
  board.formats = ['image/png', 'text/html']
  board.image = fakeImage('B', counts)
  tick()
  assert.strictEqual(counts.readImage, 2)
  // 图 → 文本 → 图（格式集回到只有图）：每次变化当 tick 处理
  board.formats = ['text/plain']
  board.text = '乙方'
  tick()
  board.formats = ['image/png']
  board.image = fakeImage('C', counts)
  tick()
  assert.strictEqual(counts.readImage, 3)
  assert.deepStrictEqual(pushed.map(p => p.type + ':' + (p.t || p.d)), [
    'IMAGE:data:image/png;base64,B', 'TEXT:乙方', 'IMAGE:data:image/png;base64,C',
  ])
})

test('无变更序号兜底：图换图且格式集不变，最迟第 5 个 tick 后检测到', () => {
  const { counts, board, pushed, tick } = setup({ withCounter: false })
  tick()
  board.image = fakeImage('B', counts)
  for (let i = 0; i < 4; i++) tick()
  assert.deepStrictEqual(pushed, [], '节流窗口内不读图')
  tick()
  assert.deepStrictEqual(pushed.map(p => p.d), ['data:image/png;base64,B'])
  assert.strictEqual(counts.readImage, 2)
})

test('startChangeCounter：非 darwin 返回 null；darwin 解析 helper 输出的最后一行，退出后回 null', () => {
  assert.strictEqual(startChangeCounter({ platform: 'win32', spawnFn: () => { throw new Error('不该起') } }), null)

  const child = new EventEmitter()
  child.stdout = new EventEmitter()
  let killed = false
  child.kill = () => { killed = true }
  let spawned = null
  const counter = startChangeCounter({ platform: 'darwin', spawnFn: (cmd, args) => { spawned = { cmd, args }; return child } })
  assert.strictEqual(spawned.cmd, '/usr/bin/osascript')
  assert.deepStrictEqual(spawned.args.slice(0, 2), ['-l', 'JavaScript'])
  assert.strictEqual(counter.get(), null)
  child.stdout.emit('data', '770\n77')
  assert.strictEqual(counter.get(), 770)
  child.stdout.emit('data', '1\n772\n')
  assert.strictEqual(counter.get(), 772)
  child.emit('exit', 0)
  assert.strictEqual(counter.get(), null, 'helper 退出后必须退回无序号，不能卡在旧值上把换图吞掉')
  counter.stop()
  assert.strictEqual(killed, true)
})
