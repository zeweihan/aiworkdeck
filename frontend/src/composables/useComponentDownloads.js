// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可选组件的应用级下载管理（dev-board#581「组件下载应该支持后台下载」）。
//
// 此前五个入口（首次登录面板 / AI 对话拦截卡 / 设置→组件管理 / 语音面板 / 录音面板）
// 各自 new 一个 useOptionalComponents 控制器，进度状态跟着组件实例走：面板一关、
// 页面一 reLaunch，进度就看不到了，从另一个入口再点还会重复发起。真正的下载本来就不在
// 前端跑（pack 在 Java 后端、模型在 Electron 主进程），所以只要把「状态」提到模块级，
// 关界面就不影响任何事。
//
// 本文件是纯工厂（依赖全部注入，node --test 直接跑）；应用里唯一的实例在
// services/componentDownloads.js。编排本身仍是 useOptionalComponents 那一份
// （pack → 模型 → ensure，顺序不能换），这里只加四件事：
//   1. 每个 packId 一个规范 item：各入口拿到的是同一个对象，进度天然共享；
//   2. 在途去重：同一 packId 在途时再点只返回同一个 Promise，不重复发起；
//   3. load() 合并而不是覆盖：半路重新拉清单不会把在途进度打回 idle、不抹掉失败原因；
//   4. claim(packId)：有入口在前台盯着时由它自己交代结果；没人盯着（后台下载、
//      入口已卸载）时，完成/失败走 deps.notify 给全局提示。

import { createOptionalComponentsController, overallPercentOf } from './useOptionalComponents.js'

const SIZE_FIELDS = ['downloadBytes', 'unpackedBytes', 'modelBytes']

export function createComponentDownloadManager(deps) {
  // 与控制器同一条规矩：状态容器必须在构造时就是调用方给的那个响应式代理
  const state = Object.assign(deps.state || {}, {
    items: [],
    loading: false,
    error: '',
    // 批量（首次登录面板「立即下载所选」）的进度，与单个任务的在途状态分开记
    running: false,
    doneCount: 0,
    totalCount: 0,
    batchIds: [],
    // 在途任务数：组件管理页据此锁住其它卡片的下载按钮（下载是带宽密集，不并发）
    activeCount: 0,
  })
  // 私有控制器只借它的编排；它自己的 state 不对外，清单以本管理器的 state.items 为准
  const ctl = createOptionalComponentsController({ ...deps, state: {} })
  const inflight = new Map()
  const claims = new Map()

  // 一律经 state 取：调用方注入 reactive 时，拿到的才是会触发重渲染的代理
  const find = (packId) => state.items.find((i) => i.packId === packId)

  function fillMissingSizes(cur, from) {
    for (const k of SIZE_FIELDS) {
      if (!(cur[k] > 0) && from[k] > 0) cur[k] = from[k]
    }
  }

  /** 把任意入口手里的 item（如 AI 对话从 payload 拼的）换成规范 item。 */
  function adopt(item) {
    if (!item || !item.packId) return item
    const cur = find(item.packId)
    if (cur) {
      fillMissingSizes(cur, item)
      return cur
    }
    state.items.push(item)
    return find(item.packId)
  }

  async function load() {
    state.loading = true
    state.error = ''
    try {
      const fresh = await ctl.load()
      state.error = ctl.state.error
      const out = []
      for (const f of fresh) {
        const cur = find(f.packId)
        if (!cur) {
          out.push(f)
          continue
        }
        if (inflight.has(f.packId) || (cur.phase === 'failed' && f.phase !== 'ready')) {
          // 在途：后端此刻仍说「未安装」，照抄会把进度打回 0；
          // 失败：照抄会把失败原因抹成「未安装」，用户找不到重试入口
          fillMissingSizes(cur, f)
        } else {
          Object.assign(cur, f, { selected: cur.selected })
        }
        out.push(cur)
      }
      // 清单里没有、但正在装的（接口临时失败时）不能从界面上消失
      for (const it of state.items) {
        if (!out.includes(it) && inflight.has(it.packId)) out.push(it)
      }
      state.items = out
    } finally {
      state.loading = false
    }
    return state.items
  }

  function fillSizes(item) {
    return ctl.fillSizes(adopt(item))
  }

  /** 装一个组件；同一 packId 在途时返回同一个 Promise。**不抛**（同 installOne）。 */
  function installOne(item) {
    const cur = adopt(item)
    const id = cur.packId
    if (inflight.has(id)) return inflight.get(id)
    state.activeCount += 1
    const p = ctl.installOne(cur).then((ok) => {
      inflight.delete(id)
      state.activeCount -= 1
      if (!((claims.get(id) || 0) > 0) && deps.notify) {
        try { deps.notify(cur, ok) } catch (e) { /* 提示失败不影响结果 */ }
      }
      return ok
    })
    inflight.set(id, p)
    return p
  }

  /** 批量逐个顺序装。批次状态在模块级：面板关掉之后照样装完剩下的。 */
  async function installAll(items) {
    const list = (items || []).filter(Boolean).map(adopt)
    state.running = true
    state.totalCount = list.length
    state.doneCount = 0
    state.batchIds = list.map((i) => i.packId)
    try {
      for (const it of list) {
        await installOne(it)
        state.doneCount += 1 // 失败也算处理完，否则总进度会永远停在那里
      }
    } finally {
      state.running = false
    }
    return list.every((i) => i.phase === 'ready')
  }

  /** 总进度只算本批（清单里早就装好的组件不该把百分比撑高）。 */
  function overallPercent() {
    return overallPercentOf(state.batchIds.map(find).filter(Boolean))
  }

  function isInstalling(packId) {
    return inflight.has(packId)
  }

  /**
   * 前台认领：返回释放函数（幂等）。认领期间任务结束不弹全局提示——由认领的入口
   * 自己交代（面板关自己、AI 对话重发或提示可重试、语音面板刷新音色）。
   * 入口点「后台下载」或被卸载时释放，结果就交给全局提示。
   */
  function claim(packId) {
    claims.set(packId, (claims.get(packId) || 0) + 1)
    let released = false
    return () => {
      if (released) return
      released = true
      claims.set(packId, (claims.get(packId) || 1) - 1)
    }
  }

  return { state, load, fillSizes, adopt, installOne, installAll, overallPercent, isInstalling, claim }
}
