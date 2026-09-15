// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可选组件的下载编排（设计 §3.1 / §4.1）。
//
// 一个组件在 UI 上是「一次下载」，底层是两条通道顺序执行：
//   1. native pack（后端 /api/packs/{id}/install + 轮询 status）——Python 运行时
//   2. model-manager（Electron 主进程）——模型权重
//   3. host.services.ensure(service) 把服务拉起来
// **顺序不能换**：模型下载器本身跑在 pack 的 venv 里（model-manager.js 的 PYTHONPATH
// 指向 pack 的 lib/），先下模型必然 ModuleNotFoundError。
//
// 依赖全部从参数注入：面板、组件管理页、AI 对话弹窗共用同一份编排，
// 单测则用桩函数跑完整条路径（不需要真后端、不需要 Electron）。
// 这也是本文件刻意不 import `@/services/api.js` / `@/utils/host.js` 的原因——
// 那两个用 @/ 别名，node --test 解析不了；接线留给调用方的 .vue。

export const PACK_LOCALE_KEY = {
  'pptx-runtime': 'pptxRuntime',
  'mineru-runtime': 'mineruRuntime',
  'kokoro-runtime': 'kokoroRuntime',
  'asr-runtime': 'asrRuntime',
}

// 一个组件内部的三段权重。模型段最重（3GB 对 250MB），启动段只留一点让进度条别在
// 99% 上停死。三个数加起来必须是 100。
const WEIGHT = { runtime: 40, model: 55, starting: 5 }

const POLL_MS = 1000

export function createOptionalComponentsController(deps) {
  // 状态容器可以由调用方注入（.vue 里传 `reactive({})`）。必须在这里就拿到那个代理：
  // 控制器内部的写全部走闭包变量 state，事后再 `controller.state = reactive(state)`
  // 只会得到一个「读得到、不触发重渲染」的壳——写落在原始对象上，代理的 setter
  // 一次都不会被调用，进度条永远停在 0。
  const state = Object.assign(deps.state || {}, {
    items: [],
    loading: false,
    running: false,
    doneCount: 0,
    totalCount: 0,
    error: '',
  })

  const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)))

  async function load() {
    state.loading = true
    state.error = ''
    try {
      const res = await deps.optionalComponents()
      const list = (res && res.components) || []
      state.items = list.map((c) => ({
        ...c,
        localeKey: PACK_LOCALE_KEY[c.packId] || c.packId,
        // phase: idle / runtime / model / starting / ready / failed
        phase: c.installed && (!c.modelId || c.modelInstalled) ? 'ready' : 'idle',
        percent: 0,
        error: '',
        selected: false,
      }))
    } catch (e) {
      state.error = (e && e.message) || String(e)
      state.items = []
    } finally {
      state.loading = false
    }
    return state.items
  }

  /** 体积未知（后端没有 manifest 快照）时才去打 /info——那条会真发网络请求。 */
  async function fillSizes(item) {
    if (!item || item.downloadBytes > 0) return item
    try {
      const info = await deps.packInfo(item.packId)
      if (info && info.totalSize) item.downloadBytes = info.totalSize
      if (info && info.unpackedSize) item.unpackedBytes = info.unpackedSize
    } catch (e) {
      // 镜像不可达：体积保持未知，卡片显示 components.sizeUnknown，不拦下载按钮
    }
    return item
  }

  /** pack 段：装 + 轮询到 ready / failed。返回 true = 就绪。 */
  async function installPack(item) {
    if (item.installed) return true
    item.phase = 'runtime'
    item.percent = 0
    await deps.packInstall(item.packId)
    for (;;) {
      const res = await deps.packStatus(item.packId)
      const st = (res && res.status) || {}
      if (st.bytesTotal > 0) {
        item.percent = Math.min(99, Math.round((st.bytesDownloaded || 0) / st.bytesTotal * 100))
      }
      if (st.state === 'ready') {
        item.installed = true
        item.percent = 100
        return true
      }
      if (st.state === 'failed' || st.state === 'revoked') {
        item.phase = 'failed'
        item.error = st.error || st.state
        return false
      }
      await sleep(POLL_MS)
    }
  }

  /**
   * 模型段：交给主进程下，进度经 onModelProgress 事件流回来（与组件管理页同一条流）。
   *
   * 完成信号只能取事件：`host.model.download` 走 IPC 打到 model-manager.download()，
   * 那个方法 spawn 完下载器就 `return {ok:true}`，它 resolve 的时刻模型一个字节都还没落盘。
   * 只 await 它就会在模型没下完时去 ensure(service)，服务起来后找不到权重。
   */
  async function installModel(item) {
    if (!item.modelId || item.modelInstalled) return true
    item.phase = 'model'
    item.percent = 0
    return new Promise((resolve) => {
      let unsub = () => {}
      const finish = (ok, msg) => {
        try { unsub() } catch (e) { /* ignore */ }
        if (!ok) { item.phase = 'failed'; item.error = msg || '' }
        else { item.modelInstalled = true; item.percent = 100 }
        resolve(ok)
      }
      unsub = deps.onModelProgress((evt) => {
        if (!evt || evt.id !== item.modelId) return
        if (evt.phase === 'progress' && typeof evt.percent === 'number') item.percent = evt.percent
        else if (evt.phase === 'done') finish(true)
        else if (evt.phase === 'error') finish(false, evt.message)
      })
      Promise.resolve(deps.modelDownload(item.modelId)).catch((e) => {
        const msg = (e && e.message) || String(e)
        // model-manager.download() 对已经装好的模型直接抛 `${id} already installed`
        // （desktop/main/services/model-manager.js:195）。重试一个「运行时装了、模型也装了、
        // 只差 ensure」的组件必然撞上这条——模型就在盘上，这一段该算成功，
        // 不能把整张卡打成 failed 让用户永远重试不出去。
        if (/already installed/i.test(msg)) finish(true)
        else finish(false, msg)
      })
    })
  }

  /**
   * 装一个组件。**不抛**：面板要能「一个失败、后面照跑」，抛出去会把 installAll 打断。
   */
  async function installOne(item) {
    item.error = ''
    try {
      if (!(await installPack(item))) return false
      if (!(await installModel(item))) return false
      item.phase = 'starting'
      item.percent = 0
      const res = await deps.ensureService(item.service)
      if (res && res.ok === false && !res.disabled) {
        item.phase = 'failed'
        item.error = res.message || 'service start failed'
        return false
      }
      item.phase = 'ready'
      item.percent = 100
      return true
    } catch (e) {
      item.phase = 'failed'
      item.error = (e && e.message) || String(e)
      return false
    }
  }

  /** 逐个顺序装（不并发：下载是带宽与磁盘 IO 密集，并发只会互相拖慢并让进度条乱跳）。 */
  async function installAll(items) {
    const list = (items || []).filter(Boolean)
    state.running = true
    state.totalCount = list.length
    state.doneCount = 0
    try {
      for (const item of list) {
        await installOne(item)
        state.doneCount += 1 // 失败也算处理完，否则总进度会永远停在那里
      }
    } finally {
      state.running = false
    }
    return list.every((i) => i.phase === 'ready')
  }

  /** 总进度：每个组件等权，组件内按 runtime/model/starting 三段加权。 */
  function overallPercent() {
    const list = state.items.filter((i) => i.selected || i.phase !== 'idle')
    const scope = list.length ? list : state.items
    return overallPercentOf(scope)
  }

  return { state, load, fillSizes, installOne, installAll, overallPercent }
}

/** 一组组件的总进度（应用级下载管理按「本批」复用这一份算法）。 */
export function overallPercentOf(scope) {
  if (!scope.length) return 0
  let sum = 0
  for (const i of scope) {
    if (i.phase === 'ready') { sum += 100; continue }
    if (i.phase === 'failed') { sum += 100; continue } // 失败也不再前进，按处理完计
    if (i.phase === 'runtime') sum += WEIGHT.runtime * (i.percent / 100)
    else if (i.phase === 'model') sum += WEIGHT.runtime + WEIGHT.model * (i.percent / 100)
    else if (i.phase === 'starting') sum += WEIGHT.runtime + WEIGHT.model
  }
  return Math.round(sum / scope.length)
}

export const PROMPTED_PREF_KEY = 'optionalComponentsPromptedVersion'

/** 大版本号（0.38.0 → 0.38）。小版本补丁不该让面板重新弹一次。 */
function majorOf(v) {
  const parts = String(v || '').split('.')
  return parts.length >= 2 ? parts[0] + '.' + parts[1] : String(v || '')
}

/**
 * 首次登录后要不要弹「可选组件」面板（设计 §4.1）。
 * 判据四条全要满足：桌面端 / 接口真的回了组件 / 存在未装的（运行时或模型缺任一都算）/
 * 本大版本没提示过。标记存 electron prefs，重装才重置——localStorage 被清一次
 * 用户就会被重新打扰一遍。
 */
export function shouldPromptOptionalComponents({ items, promptedVersion, appVersion, isDesktop }) {
  if (!isDesktop) return false
  const list = items || []
  if (!list.length) return false
  const anyMissing = list.some((i) => !i.installed || (i.modelId && !i.modelInstalled))
  if (!anyMissing) return false
  return majorOf(promptedVersion) !== majorOf(appVersion)
}
