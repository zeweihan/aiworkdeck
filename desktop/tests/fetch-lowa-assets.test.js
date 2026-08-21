const test = require('node:test')
const assert = require('node:assert')
const { checkMagic } = require('../scripts/fetch-lowa-assets')

// dev-board#74 稳定性审计：checkMagic 对 wasm/font 只查开头几个字节的形状，
// 没有像同一函数里的 'js'/'blob' 那样加最小体积下限。截断的下载（连接中途断开）
// 只要恰好保留了正确的 magic 字节，就会被当成有效文件收下并缓存，
// 桌面端首次打开文档时引擎会因为文件不完整而崩溃或白屏，且这一坏就是永坏
// （cachedOk 复验用的是同一套宽松规则）。

function wasmHeader(size) {
  const buf = Buffer.alloc(size, 0)
  buf[0] = 0x00; buf[1] = 0x61; buf[2] = 0x73; buf[3] = 0x6d // \0asm
  return buf
}

function fontHeader(size) {
  const buf = Buffer.alloc(size, 0)
  buf.write('OTTO', 0, 'latin1')
  return buf
}

test('wasm: 64 字节的截断文件（magic 正确）必须被拒', () => {
  assert.throws(() => checkMagic(wasmHeader(64), 'wasm', 'lowa/soffice.wasm'), /validation failed/)
})

test('font: 64 字节的截断文件（OTTO 开头）必须被拒', () => {
  assert.throws(() => checkMagic(fontHeader(64), 'font', 'cjk.ttc'), /validation failed/)
})

test('wasm: 正常大小（>=1KB）且 magic 正确的文件仍然通过', () => {
  assert.doesNotThrow(() => checkMagic(wasmHeader(4096), 'wasm', 'lowa/soffice.wasm'))
})

test('font: 正常大小（>=1KB）且 magic 正确的文件仍然通过', () => {
  assert.doesNotThrow(() => checkMagic(fontHeader(4096), 'font', 'cjk.ttc'))
})

test('wasm: magic 错误时不论大小都被拒（既有行为不受影响）', () => {
  const buf = Buffer.alloc(4096, 0)
  assert.throws(() => checkMagic(buf, 'wasm', 'lowa/soffice.wasm'), /validation failed/)
})
