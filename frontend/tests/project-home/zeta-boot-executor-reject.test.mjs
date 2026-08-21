// 审计（dev-board#74）HIGH：bootZetaOffice() 的 async Promise-executor 吞掉首个
// await 之后抛出的异常，boot 的 promise（连同编辑器加载遮罩）永远悬空。
//
// 病灶：`new Promise(async (resolve, reject) => {...})`——传给 Promise 构造器的
// executor 只要是 async 函数，构造器的隐式 try/catch 只兜得住"挂起在第一个 await
// 之前"的同步前缀；一旦挂起过，之后再抛的异常会变成这个 async 函数自己那个被
// 丢弃的返回 promise 的 unhandled rejection，压根不会调用 reject。s.onload 同样
// 中招——它是普通（非 async）DOM 回调，里面的同步抛出没有任何东西兜底。
//
// 这个模块本身是"framework-agnostic"的纯 ES module（文件头注释明说 NO Vue），
// 可以直接 import 真跑，不需要走本仓那套"抠 <script> 再 new Function"的套路。
// 用最小的 document/self 桩，制造"首个 await 之后同步抛异常"与"s.onload 内部同步
// 抛异常"两种触发场景，断言 boot() 返回的 promise 必须在有限时间内 reject，
// 而不是永远不 settle。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { bootZetaOffice } from '../../src/composables/zetaOfficeBoot.js'

// 真实 setTimeout（在 shim globalThis.setTimeout 之前捕获），供测试自己的"等一小段
// 时间看 promise 有没有 settle"计时，不受被测代码内部计时器影响。
const realSetTimeout = globalThis.setTimeout

// bootZetaOffice 内部起了一个 1s 一跳的 setInterval（保持 Qt 窗口跟画布同尺寸），
// 只有调用方拿到 resolve 出的 dispose() 才会清掉；本测试文件不少路径走的是
// reject（早退），根本拿不到 dispose。不 unref 的话这个定时器会让 node --test
// 进程永远退不出去（不是本条要修的缺陷——这里只是让测试进程能正常收尾）。
const realSetInterval = globalThis.setInterval
globalThis.setInterval = (fn, ms, ...args) => {
  const t = realSetInterval(fn, ms, ...args)
  if (t && typeof t.unref === 'function') t.unref()
  return t
}

const delay = (ms, val) => new Promise((r) => realSetTimeout(() => r(val), ms))

// 把 bootZetaOffice() 的 promise 与一个短暂超时哨兵赛跑：赢家是超时哨兵，
// 说明 promise 在这段时间内没有 settle（复现"永远悬空"）；赢家是 reject/resolve，
// 说明 promise 确实 settle 了。
async function raceSettle(promise, timeoutMs = 300) {
  const TIMEOUT = Symbol('timeout')
  const settled = promise.then(
    (v) => ({ ok: true, value: v }),
    (e) => ({ ok: false, error: e }))
  const result = await Promise.race([settled, delay(timeoutMs, TIMEOUT)])
  return result
}

test('首个 await 之后（fontFetches 完成之后的同步代码）抛出的异常必须 reject，而不是永远悬空', async () => {
  // 不传 fontUrl/fontUrls：fontList 为空，Promise.all([]) 立即 resolve，"首个 await"
  // 瞬间跨过——随后进入的是纯同步代码（构造 CJK_ALIAS_GROUPS、Module 对象……），
  // 让 document.createElement 在这段同步代码里抛异常，精确复现"过了第一个 await
  // 之后的同步抛出"这个失败模式。
  globalThis.document = {
    createElement: () => { throw new Error('boom-create-element') },
    body: { appendChild: () => {} },
  }
  try {
    // sofficeBaseUrl:'' 跳过需要 globalThis.location 的绝对化分支，聚焦本条要测的路径
    const result = await raceSettle(bootZetaOffice({ canvas: {}, sofficeBaseUrl: '' }))
    assert.notEqual(result, undefined)
    assert.equal(result.ok, false, 'boot() 的 promise 必须在合理时间内 reject——不能永远挂起（这正是本条缺陷的表现）')
    assert.match(String(result.error && result.error.message), /boom-create-element/)
  } finally {
    delete globalThis.document
  }
})

test('s.onload 内部同步抛出的异常（如 Module.uno_main 不存在）必须 reject', async () => {
  // 模拟"脚本加载完成"：真实浏览器里 onload 是异步触发的，这里用同步调用简化——
  // 触发条件本身与"异步 vs 同步调用 onload"无关，只与"onload 内部抛出能不能传出去"
  // 有关。bootZetaOffice 自己会把不带 uno_main 的 Module 挂到 globalThis.Module 上
  // （真实场景里 uno_main 是 soffice.js 加载完之后才挂上去的），我们不加载真实
  // soffice.js，所以 Module.uno_main 天然是 undefined，访问它的 .then 会抛 TypeError。
  globalThis.document = {
    createElement: () => ({}),
    body: { appendChild: (el) => { if (el.onload) el.onload() } },
  }
  try {
    const result = await raceSettle(bootZetaOffice({ canvas: {}, sofficeBaseUrl: '' }))
    assert.notEqual(result, undefined)
    assert.equal(result.ok, false, 's.onload 内部的同步抛出也必须 reject，不能被吞掉')
    assert.match(String(result.error && result.error.message),
      /uno_main|Cannot read propert/i)
  } finally {
    delete globalThis.document
    delete globalThis.Module
  }
})

test('正常路径不受影响：onload 里 uno_main 正常 resolve 时 boot() 照常 resolve', async () => {
  let resolveUnoMain
  globalThis.document = {
    createElement: () => ({}),
    body: {
      appendChild: (el) => {
        // 先把 Module.uno_main 换成一个受控 promise，再触发 onload
        globalThis.Module.uno_main = new Promise((r) => { resolveUnoMain = r })
        if (el.onload) el.onload()
        resolveUnoMain({ onmessage: null })
      },
    },
  }
  try {
    const result = await raceSettle(bootZetaOffice({ canvas: {}, sofficeBaseUrl: '' }))
    assert.equal(result.ok, true, '正常路径必须照常 resolve，本条修复不能引入新的失败')
    assert.ok(result.value && result.value.port, 'resolve 值必须带上 port')
  } finally {
    delete globalThis.document
    delete globalThis.Module
  }
})
