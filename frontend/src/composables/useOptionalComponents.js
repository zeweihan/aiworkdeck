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

// 轮询里连续这么久「阶段 / 字节 / 文件数 / 百分比」一样都没变，就判卡死（dev-board#1015）。
// 后端解压每写一个文件都会推进 filesDone，正常安装不会三分钟纹丝不动。
export const STALL_MS = 3 * 60 * 1000

// pack 段内部的细分阶段在「运行时 40%」里的占比：下载占大头，解压其次。
// 只用于总进度（overallPercentOf）；卡片上显示的是每个阶段自己的百分比。
const RUNTIME_SPAN = {
  downloading: [0, 60],
  verifying: [60, 65],
  extracting: [65, 95],
  checking: [95, 97],
  finalizing: [97, 100],
}

// 后端 state（老契约）→ 细分阶段；后端没带 phase 字段（老后端）时用它兜底
const STATE_TO_STAGE = { downloading: 'downloading', verifying: 'verifying', installing: 'extracting' }
const IN_FLIGHT = new Set(['downloading', 'verifying', 'installing'])

// 前端自己判出来的失败没有后端文案，给一句默认话；有 deps.t 时按界面语言取 locale
const FALLBACK_ERROR = {
  stalled: '长时间没有进展，已停止等待。请重试；仍失败可查看 ~/.aiworkdeck/logs 下的后端日志。',
  backendRestarted: '后台服务已重启，下载被中断，请重试。',
}
const ERROR_LOCALE_KEY = { stalled: 'errorStalled', backendRestarted: 'errorBackendRestarted' }

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
  const now = deps.now || (() => Date.now())

  function failWith(item, errorKey) {
    item.phase = 'failed'
    item.stageKey = 'preparing'
    item.errorKey = errorKey
    const k = 'components.' + ERROR_LOCALE_KEY[errorKey]
    let msg = ''
    try { msg = deps.t ? deps.t(k) : '' } catch (e) { msg = '' }
    item.error = msg && msg !== k ? msg : FALLBACK_ERROR[errorKey]
  }

  /** 把一帧后端 status 映射到卡片：stage（细分阶段）、percent（本阶段）、runtimePercent（总进度用） */
  function applyStatus(item, st) {
    const stage = RUNTIME_SPAN[st.phase] ? st.phase : (STATE_TO_STAGE[st.state] || 'downloading')
    let pct = null
    if (stage === 'downloading') {
      if (st.bytesTotal > 0) pct = Math.min(100, Math.round((st.bytesDownloaded || 0) / st.bytesTotal * 100))
    } else if (typeof st.phasePercent === 'number' && st.phasePercent >= 0) {
      pct = Math.min(100, st.phasePercent)
    }
    item.stage = stage
    item.stageKey = stage
    item.percent = pct == null ? 0 : pct
    item.percentKnown = pct != null
    const [lo, hi] = RUNTIME_SPAN[stage]
    const within = pct == null ? 0 : pct / 100
    item.runtimePercent = Math.max(item.runtimePercent || 0, Math.round(lo + (hi - lo) * within))
  }

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

  /**
   * pack 段：装 + 轮询到 ready / failed。返回 true = 就绪。
   *
   * 三条出口之外不许无限转圈（dev-board#1015）：
   *   - 后端 ready / failed / revoked：照后端说的办；
   *   - 在途中后端回 not_installed：它把在途记录丢了（后端被重启），判失败让用户重试；
   *     安装请求已返回时后端必然已把状态置为 downloading，所以这里不存在「还没开始」；
   *   - STALL_MS 内状态签名没有任何变化：判卡死。
   */
  async function installPack(item) {
    if (item.installed) return true
    item.phase = 'runtime'
    item.stage = 'downloading'
    item.stageKey = 'downloading'
    item.percent = 0
    item.percentKnown = false
    item.runtimePercent = 0
    item.errorKey = ''
    await deps.packInstall(item.packId)
    let lastSig = null
    let lastChange = now()
    for (;;) {
      const res = await deps.packStatus(item.packId)
      const st = (res && res.status) || {}
      if (st.state === 'ready') {
        item.installed = true
        item.percent = 100
        item.runtimePercent = 100
        return true
      }
      if (st.state === 'failed' || st.state === 'revoked') {
        item.phase = 'failed'
        item.stageKey = 'preparing'
        item.error = st.error || st.state
        return false
      }
      if (!IN_FLIGHT.has(st.state)) {
        failWith(item, 'backendRestarted')
        return false
      }
      applyStatus(item, st)
      const sig = [st.state, st.phase, st.bytesDownloaded, st.filesDone, st.bytesUnpacked, st.phasePercent].join('|')
      if (sig !== lastSig) {
        lastSig = sig
        lastChange = now()
      } else if (now() - lastChange >= STALL_MS) {
        failWith(item, 'stalled')
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
    item.stageKey = 'model'
    item.percent = 0
    return new Promise((resolve) => {
      let unsub = () => {}
      const finish = (ok, msg) => {
        try { unsub() } catch (e) { /* ignore */ }
        if (!ok) { item.phase = 'failed'; item.stageKey = 'preparing'; item.error = msg || '' }
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
    item.errorKey = ''
    try {
      if (!(await installPack(item))) return false
      if (!(await installModel(item))) return false
      item.phase = 'starting'
      item.stageKey = 'starting'
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
    // runtimePercent：pack 段各细分阶段折算后的进度（解压时卡片百分比会从 0 重来，总进度不能跟着退）
    if (i.phase === 'runtime') sum += WEIGHT.runtime * ((typeof i.runtimePercent === 'number' ? i.runtimePercent : i.percent) / 100)
    else if (i.phase === 'model') sum += WEIGHT.runtime + WEIGHT.model * (i.percent / 100)
    else if (i.phase === 'starting') sum += WEIGHT.runtime + WEIGHT.model
  }
  return Math.round(sum / scope.length)
}

export const PROMPTED_PREF_KEY = 'optionalComponentsPromptedVersion'
/** 上次提示时清单里有哪些组件（与 PROMPTED_PREF_KEY 并列落 prefs，dev-board#751） */
export const PROMPTED_PACKS_PREF_KEY = 'optionalComponentsPromptedPacks'

/** 缺失 = 运行时没装，或有配套模型而模型没下。 */
function missingPackIds(items) {
  return (items || [])
    .filter((i) => i && (!i.installed || (i.modelId && !i.modelInstalled)))
    .map((i) => i.packId)
}

/**
 * 「提示过哪些组件」的新值：旧集合 ∪ 本次清单里的<b>全部</b>组件（不只是缺的）。
 *
 * <p>记全部而不是只记缺的，是为了让「用户自己从 设置→组件管理 卸载一个组件」不被当成
 * 新组件——它早就被提示过了。并集又保证这个集合单调增，不会因为某次清单拉不全而回退。
 */
export function mergePromptedPackIds(prompted, items) {
  const out = new Set(Array.isArray(prompted) ? prompted : [])
  for (const i of items || []) {
    if (i && i.packId) out.add(i.packId)
  }
  return [...out].sort()
}

/**
 * 首次登录后要不要弹「可选组件」面板（设计 §4.1）。
 * 判据：桌面端 / 接口真的回了组件 / 存在未装的（运行时或模型缺任一都算）/
 * 缺的里面有<b>上次没提示过</b>的。
 *
 * <p>dev-board#751 之前这一条是「本大版本没提示过」，于是每个 0.x 发版都把用户已经
 * 点过「稍后再说」的同一批组件再问一遍。现在记的是组件集合：同一批不再重弹，
 * 真出现新组件才弹。标记存 electron prefs，重装才重置（重装后弹一次是设计允许的）。
 *
 * @param promptedPackIds 上次记下的组件集合；null/undefined = 老标记（0.46 及以前，
 *        只记了版本号）。那种情况按「这批都提示过」处置——用户已经答过一次了。
 */
export function shouldPromptOptionalComponents({ items, promptedVersion, promptedPackIds, isDesktop }) {
  if (!isDesktop) return false
  const list = items || []
  if (!list.length) return false
  const missing = missingPackIds(list)
  if (!missing.length) return false
  if (Array.isArray(promptedPackIds)) return missing.some((id) => !promptedPackIds.includes(id))
  return !promptedVersion
}
