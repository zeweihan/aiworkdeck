// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 窗格心跳（dev-board#717）：让云端知道「这个账号现在开着哪些文档的窗格」，
 * 供别的窗格跨文档读写（ref_list / ref_read / ref_edit 的 open: 来源）。
 *
 * 启动即发一次、之后每 30 秒一次；云端 90 秒没收到就当窗格已关（PaneRegistry）。
 * 会话身份变了（新对话/切会话/换项目）由调用方立刻 beatNow() 补发，别让云端
 * 拿着旧 conversationId 往一条没人听的会话里推命令。
 *
 * getState() 返回 null（未登录）或没有 paneId 时不发。任何失败一律吞掉：
 * 心跳跑在定时器里，抛出去就是没人接的 unhandled rejection；漏一次无害，
 * 下一轮再发，云端的过期时间本来就留了两轮余量。
 */
export function startHeartbeat({ getState, post, intervalMs = 30000,
  setIntervalFn = setInterval, clearIntervalFn = clearInterval }) {
  async function beatNow() {
    try {
      const s = getState()
      if (!s || !s.paneId) return
      await post(s)
    } catch (e) {
      // 心跳失败无害，下一轮再发
    }
  }
  const timer = setIntervalFn(beatNow, intervalMs)
  beatNow()
  return { beatNow, stop: () => clearIntervalFn(timer) }
}
