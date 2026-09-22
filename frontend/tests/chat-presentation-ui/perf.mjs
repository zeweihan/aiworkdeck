// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 长会话性能测量（dev-board#811 K31）。跑在 run.mjs 已经开好的那一页上，复用 K12 的
// 200 轮夹具（window.loadManyTurns）。
//
// **阈值刻意宽松**：这里要拦的是「整片回归」——比如有人把 chatTurns 改回每 token 全量
// 重建、把 markdown 改回每帧整篇重解析。本机与 CI 的机器速度差几倍，卡死阈值只会换来
// 一条间歇性红线，而间歇性红线的下场是被人加 `|| true`。

/** 首屏：200 轮历史回灌到可交互。 */
export async function measureFirstPaint(page) {
  const ms = await page.evaluate(async () => {
    const t0 = performance.now()
    await window.loadManyTurns(200)
    return performance.now() - t0
  })
  await page.waitForFunction(() => window.chatState.chatTurns.length === 200)
  return ms
}

/**
 * 流式：在 200 轮之后追加一条 3000 token 的回答，逐 token 让出任务队列（真实 SSE 就是
 * 一条一条到的），量主线程代价。
 *
 * 让出用 MessageChannel 而不是 setTimeout(0)：后者嵌套 5 层之后被浏览器钳到 4ms，
 * 3000 个 token 光等就要 12 秒，量到的会是定时器而不是渲染。
 */
export async function measureStreaming(page, { tokens = 3000 } = {}) {
  return page.evaluate(async (tokenCount) => {
    const yieldTask = () => new Promise(resolve => {
      const channel = new MessageChannel()
      channel.port1.onmessage = () => resolve()
      channel.port2.postMessage(0)
    })
    // chatTurns 的 computed 每次求值都返回新数组，所以身份比较的 watcher 触发次数
    // 就是重建次数。flush:'sync' 是为了不和模板渲染那次合并掉。
    let builds = 0
    const stopWatch = window.__vueWatch(() => window.chatState.chatTurns, () => { builds += 1 }, { flush: 'sync' })

    const md = window.__markdownInstance()
    const originalRender = md.render.bind(md)
    let renders = 0
    let parseMs = 0
    let parsedChars = 0
    md.render = (text, env) => {
      renders += 1
      parsedChars += (text || '').length
      const t0 = performance.now()
      const html = originalRender(text, env)
      parseMs += performance.now() - t0
      return html
    }

    const longTasks = []
    let observer = null
    try {
      observer = new PerformanceObserver(list => { for (const entry of list.getEntries()) longTasks.push(entry.duration) })
      observer.observe({ entryTypes: ['longtask'] })
    } catch { /* longtask 不是每个内核都有；缺了就只看帧与墙钟 */ }

    window.chatState.bubbles.push({
      id: 'perf-stream', role: 'ASSISTANT', content: '', isStreaming: true,
      processes: [], artifacts: [], planTodos: [], timeline: []
    })
    const bubble = window.chatState.bubbles.at(-1)
    await new Promise(resolve => requestAnimationFrame(resolve))

    // 每 40 个 token 收一段，接近真实法律文书回答的段落密度
    const chunks = []
    for (let i = 0; i < tokenCount; i += 1) chunks.push(i % 40 === 39 ? '。\n\n' : '按约履行义务的')

    let worstFrame = 0
    let frames = 0
    let previous = performance.now()
    let handle = requestAnimationFrame(function loop() {
      const now = performance.now()
      if (frames > 0) worstFrame = Math.max(worstFrame, now - previous)
      previous = now
      frames += 1
      handle = requestAnimationFrame(loop)
    })

    const t0 = performance.now()
    for (const chunk of chunks) {
      bubble.content += chunk
      await yieldTask()
    }
    await new Promise(resolve => requestAnimationFrame(resolve))
    await new Promise(resolve => requestAnimationFrame(resolve))
    const wall = performance.now() - t0

    cancelAnimationFrame(handle)
    stopWatch()
    md.render = originalRender
    if (observer) { observer.takeRecords(); observer.disconnect() }

    return {
      wall, builds, renders, parseMs, parsedChars, worstFrame, frames,
      longCount: longTasks.length,
      longTotal: longTasks.reduce((sum, duration) => sum + duration, 0),
      chars: bubble.content.length
    }
  }, tokens)
}

export function report(label, stream) {
  console.log(`  [${label}] 墙钟 ${stream.wall.toFixed(0)}ms / ${stream.frames} 帧，最坏一帧 ${stream.worstFrame.toFixed(1)}ms`)
  console.log(`  [${label}] chatTurns 重建 ${stream.builds} 次；markdown 解析 ${stream.renders} 次、累计 ${stream.parseMs.toFixed(0)}ms、喂进解析器 ${(stream.parsedChars / 1e6).toFixed(2)}M 字符`)
  console.log(`  [${label}] 长任务 ${stream.longCount} 个、累计 ${stream.longTotal.toFixed(0)}ms`)
}
