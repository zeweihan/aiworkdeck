// 审计（dev-board#74）：上传永久失败时 stopRecording() 永不返回，status 钉死在
// 'stopping'，面板与顶部胶囊的停止按钮永远禁用，会议在后端一直是 RECORDING。
//
// 这里不做源码文本断言，而是真跑一遍引擎：把 meetingRecorder.js 的四处 import
// 改写成本目录的替身模块（node 解析不了 uni 的 @/ 别名），落到临时文件再 import。
// 替身的上传端点必然失败，等价于鉴权过期或文件被后台删掉。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as stubs from './stubs.mjs'

const realSetTimeout = globalThis.setTimeout
const sleep = (ms) => new Promise((r) => realSetTimeout(r, ms))
// 竞速用的兜底计时器：不 unref 的话，用例过了之后进程还要空等它到点
const sleepUnref = (ms) => new Promise((r) => { const h = realSetTimeout(r, ms); if (h.unref) h.unref() })

function buildModuleUrl() {
  const src = readFileSync(new URL('../../src/utils/meetingRecorder.js', import.meta.url), 'utf8')
  const stubsUrl = new URL('./stubs.mjs', import.meta.url).href
  const rewritten = src
    .replace(/from 'vue'/, `from '${stubsUrl}'`)
    .replace(/from '@\/services\/api\.js'/, `from '${stubsUrl}'`)
    .replace(/from '@\/utils\/auth\.js'/, `from '${stubsUrl}'`)
    .replace(/from '@\/i18n'/, `from '${stubsUrl}'`)
  const out = join(tmpdir(), `meeting-recorder-under-test-${process.pid}.mjs`)
  writeFileSync(out, rewritten, 'utf8')
  return out
}

class FakeRecorder {
  constructor() { this.state = 'recording'; FakeRecorder.instance = this }
  start() {}
  stop() { this.state = 'inactive'; if (this.onstop) this.onstop() }
}
FakeRecorder.isTypeSupported = () => false

function installGlobals() {
  Object.defineProperty(globalThis, 'navigator', {
    value: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [] }) } },
    configurable: true, writable: true,
  })
  globalThis.MediaRecorder = FakeRecorder
  // 只提供 XHR 的形状；本模块在非 H5 条件编译分支里会立刻 reject，
  // 走的是同一条「上传失败」路径，重试逻辑不区分失败原因。
  globalThis.XMLHttpRequest = class {
    open() {} setRequestHeader() {} send() {} abort() {}
  }
  // 退避是 1s 起步的真等待，测试里压成 1ms
  globalThis.setTimeout = (fn, ms, ...rest) => realSetTimeout(fn, ms >= 1000 ? 1 : ms, ...rest)
}

const attemptOf = (err) => {
  const m = /"attempt":(\d+)/.exec(err || '')
  return m ? Number(m[1]) : 0
}

test('上传永久失败时 stopRecording 会封顶放弃，而不是把 status 钉死在 stopping', async () => {
  installGlobals()
  const rec = await import(buildModuleUrl())
  try {
    await rec.startRecording('p1')
    assert.equal(rec.recorderState.status, 'recording')

    FakeRecorder.instance.ondataavailable({ data: { size: 1024 } })

    // 先确认录音进行期间仍然是无限重试（这条是刻意保留的耐久性设计，不许被封顶改掉）
    const deadline = Date.now() + 5000
    while (attemptOf(rec.recorderState.error) < 8 && Date.now() < deadline) await sleep(5)
    assert.ok(attemptOf(rec.recorderState.error) >= 8,
      '录音进行期间应当持续重试，实际 error=' + rec.recorderState.error)
    assert.equal(rec.recorderState.status, 'recording')

    const result = await Promise.race([
      rec.stopRecording(),
      sleepUnref(5000).then(() => 'TIMEOUT'),
    ])
    assert.notEqual(result, 'TIMEOUT',
      'stopRecording 必须返回：否则 status 永远停在 stopping，停止按钮永远禁用')
    assert.equal(rec.recorderState.status, 'idle')
    assert.equal(stubs.finishCallCount(), 1, '放弃上传后仍要回写 finish，别把会议留在 RECORDING')
    assert.ok(rec.recorderState.error, '放弃上传要留一条报错给用户')
  } finally {
    globalThis.setTimeout = realSetTimeout
  }
})
