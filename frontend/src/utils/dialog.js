// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 应用内对话框（dev-board#849）：showDialog / showSheet 两个 Promise 入口 +
// uni.showModal / uni.showActionSheet 的全局接管。纯逻辑在同目录 dialogCore.js。
//
// ── 为什么宿主不写在 App.vue 模板里 ──
// uni-h5 的 setupApp 会把 App 组件的 render 整个换成 LayoutComponent
// （uni-h5.es.js 的 setupApp → before(comp2) → `comp2.render = render`），
// App.vue 里写的任何模板元素都不会被渲染。所以宿主照 utils/feedbackWidget.js 的先例，
// 在 <body> 下单独 createApp 一个实例，全应用一个，首次弹窗时才挂。
//
// ── 为什么接管走拦截器而不是直接改写 uni.showModal ──
// 发行构建默认开摇树（manifest 没关 h5.optimization.treeShaking），uni 的 vite 插件
// （@dcloudio/uni-h5-vite/dist/plugins/inject.js）会把源码里每一处 `uni.showModal`
// 改写成 `import { showModal } from '@dcloudio/uni-h5'` 的直接引用，而 window.uni
// 在发行包里只是个空对象（plugins/pagesJson.js 的 registerGlobalCode）。运行时去改
// `uni.showModal` 这个属性，开发服务器上看着生效、打出来的桌面包里一处都不生效。
// 拦截器表是两条路径共用的：uni 的 promisify → invokeApi 每次都会查它，invoke 返回
// false 即放弃原生弹窗（uni-h5.es.js 的 queue()）。所以 71 处调用点一行不改、
// 开发与发行两种构建行为一致。
import { createApp, reactive } from 'vue'
import AwdDialogHost from '@/components/AwdDialogHost.vue'
import { t } from '@/i18n'
import { setGlobalOverlay } from '@/utils/overlayState.js'
import {
  normalizeModalOptions,
  normalizeSheetOptions,
  createModalInvoke,
  createSheetInvoke,
} from './dialogCore.js'

const CONTAINER_ID = 'awd-dialog-host'

// 同一时刻只显示队首那一个；关掉后下一个接上（uni 原生是后一个直接覆盖前一个、
// 前一个的回调永远不回——那会让先弹的那一处的 Promise 悬空）。
const state = reactive({ queue: [] })
let seq = 0
let hostMounted = false

function ensureHost() {
  if (hostMounted) return true
  if (typeof document === 'undefined' || !document.body) return false
  try {
    let el = document.getElementById(CONTAINER_ID)
    if (!el) {
      el = document.createElement('div')
      el.id = CONTAINER_ID
      document.body.appendChild(el)
    }
    createApp(AwdDialogHost, { state }).mount(el)
    hostMounted = true
  } catch (e) {
    console.warn('[dialog] 宿主挂载失败:', e)
  }
  return hostMounted
}

// 桌面端的 BrowserView 是原生层、永远盖在 DOM 上（见 overlayState.js）：对话框开着时
// 必须持有全局浮层，让工作台把网页视图藏起来，否则框被盖住、遮罩点不到、Esc 进不来，
// 等它结果的调用方就永远挂着（dev-board#968）。队列非空即持有，清空才释放；
// 持有者键只用 'awd-dialog'，不碰别的浮层的持有。
const OVERLAY_HOLDER = 'awd-dialog'

function enqueue(kind, opts, fallback) {
  return new Promise((resolve) => {
    if (!ensureHost()) {
      resolve(fallback)
      return
    }
    state.queue.push({
      id: ++seq,
      kind,
      opts,
      // AwdDialogHost 先把自己 shift 出队再调 resolve，所以这里看到的就是剩下的队列。
      // 释放推迟一个宏任务：调用方常在上一个框的结果里同步弹下一个，立刻释放的话
      // 工作台的 watcher 会先把网页视图亮出来一帧再藏回去（闪一下）。
      resolve: (result) => {
        if (state.queue.length === 0) {
          setTimeout(() => { if (state.queue.length === 0) setGlobalOverlay(false, OVERLAY_HOLDER) }, 0)
        }
        resolve(result)
      },
    })
    setGlobalOverlay(true, OVERLAY_HOLDER)
  })
}

/**
 * 确认框。opts：{ title, content, showCancel, confirmText, cancelText, danger,
 * editable, placeholderText }（也接受 uni 的 confirmColor，红色映射成 danger）。
 * 返回 Promise<{ confirm, cancel, content? }>，content 只在 editable 且确认时有。
 */
export function showDialog(opts = {}) {
  const o = normalizeModalOptions(opts, t)
  return enqueue('modal', o, { confirm: false, cancel: true }).then((r) => {
    const confirm = !!(r && r.confirm)
    const res = { confirm, cancel: !confirm }
    if (confirm && o.editable) res.content = String((r && r.content) || '')
    return res
  })
}

/**
 * 选项表。opts：{ title, itemList, itemColor, cancelText }。
 * 返回 Promise<{ tapIndex }>，取消时 tapIndex 为 -1。
 */
export function showSheet(opts = {}) {
  const o = normalizeSheetOptions(opts, t)
  if (!o) return Promise.resolve({ tapIndex: -1 })
  return enqueue('sheet', o, { tapIndex: -1 })
}

let bridgeInstalled = false

/** 把 uni.showModal / uni.showActionSheet 转发到上面两个入口。只在 H5 入口调一次。 */
export function installUniDialogBridge() {
  if (bridgeInstalled) return
  bridgeInstalled = true
  try {
    uni.addInterceptor('showModal', { invoke: createModalInvoke(showDialog, t) })
    uni.addInterceptor('showActionSheet', { invoke: createSheetInvoke(showSheet, t) })
  } catch (e) {
    // 接管失败时退回 uni 原生弹窗，至少功能还在
    console.warn('[dialog] uni 弹窗接管失败:', e)
  }
}
