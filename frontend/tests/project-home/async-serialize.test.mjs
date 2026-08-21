// 审计（dev-board#74）确认的缺陷：DrawioEditor.vue 快速两次保存，其中一次悄无声息
// 地没做。
//
// 病灶：persist() 无并发闸；exportSvg() 把等 SVG 回包的 resolver 存进单槽
// pendingExport，第二次调用直接覆盖第一次的 resolver；draw.io 对第一次请求的回包
// 到达时只喂得到"当前槽"（已经是第二次的 resolver），第一次真正的回包随后到达时
// 槽已是 null，被直接丢弃；第一次 exportSvg() 的 await 因此永久挂起——那次保存
// 静默烂尾，不写盘、不报错、不改保存提示。
//
// 修法：createSerialQueue() 把并发调用串成一条队列，保证同一时刻只有一个任务在
// 执行，单槽也就天然安全。这里直接模拟 DrawioEditor 里那个单槽 pendingExport，
// 验证"两次并发提交后，两个 Promise 都必须 settle"。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'

test('两次并发任务都必须 settle，不能有一个永久挂起（复现单槽被覆盖的场景）', async () => {
  const run = createSerialQueue()
  let slot = null // 模拟 DrawioEditor 的单槽 pendingExport

  function submitLikeDrawioPersist(id) {
    return run(() => new Promise((resolve) => {
      slot = { id, resolve } // 占用"单槽"——如果没有并发闸，第二次调用会直接覆盖这一行
      // 模拟 draw.io 异步回包：下一个 tick 才 resolve，制造"第二次调用抢在第一次
      // 回包之前发生"的窗口
      setTimeout(() => {
        // 若两次任务真的并发执行，此时 slot 可能已经被第二次调用覆盖，
        // resolve 到的会是错误的 id——串行化之后 slot 在结算时必须仍是自己占的那个
        resolve(slot.id)
        slot = null
      }, 5)
    }))
  }

  const pA = submitLikeDrawioPersist('A')
  const pB = submitLikeDrawioPersist('B')

  // 两个 Promise 都必须 settle：没有并发闸时，A 的 resolver 会被 B 覆盖，
  // A 的 await 永久挂起，Promise.all 会一直不落地（用 race 的超时会更明显，
  // 这里直接断言两者都 resolve 到各自正确的值就足够暴露"覆盖"这个根因）。
  const [resultA, resultB] = await Promise.all([pA, pB])
  assert.equal(resultA, 'A', 'A 必须结算到自己的结果，不能被 B 的 resolver 顶替')
  assert.equal(resultB, 'B')
})

test('严格按提交顺序串行执行，不会交叠', async () => {
  const run = createSerialQueue()
  const order = []
  function submit(id, delay) {
    return run(() => new Promise((resolve) => {
      order.push(`start:${id}`)
      setTimeout(() => { order.push(`end:${id}`); resolve() }, delay)
    }))
  }
  await Promise.all([submit('A', 10), submit('B', 1)])
  // 即使 B 的任务本身更快，也必须等 A 完全结束才能开始（不能 start:B 出现在 end:A 之前）
  assert.deepEqual(order, ['start:A', 'end:A', 'start:B', 'end:B'])
})

test('前一个任务抛错不阻塞后一个任务执行', async () => {
  const run = createSerialQueue()
  await assert.rejects(run(async () => { throw new Error('boom') }))
  let ran = false
  await run(async () => { ran = true })
  assert.equal(ran, true, '前一个任务失败后，队列必须继续处理后续任务，不能整条卡死')
})

test('前一个任务失败时，run() 返回的 Promise 如实拒绝（不吞错误）', async () => {
  const run = createSerialQueue()
  await assert.rejects(run(async () => { throw new Error('save failed') }), /save failed/)
})
