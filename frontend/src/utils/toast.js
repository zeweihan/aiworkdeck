// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 统一 toast 体系（dev-board#891）：uni.showToast / hideToast / showLoading / hideLoading
// 的全局接管，照 utils/dialog.js（dev-board#849）的同一套办法——纯逻辑在同目录
// toastCore.js，接管走 uni.addInterceptor 而不是改写 uni.showToast 属性本身
// （发行构建摇树后 window.uni 是空对象，改属性在开发服务器上看着生效、打出来的包
// 一处都不生效，原因见 dialog.js 顶部注释，这里是同一个坑）。
//
// 宿主同样挂在 <body> 下单独 createApp（App.vue 的模板会被 uni 的 LayoutComponent
// 整个替换，挂不进去），全应用一个实例，首次弹 toast/loading 时才挂。
//
// 与 dialog.js 的队列不同：toast 允许多条同时可见（纵向堆叠，见 toastCore.js 的
// pushToastItem），所以这里的 state 是 { items: [], loading }，不是单队列。
import { createApp, reactive } from 'vue'
import AwdToastHost from '@/components/AwdToastHost.vue'
import {
  createShowToastInvoke,
  createHideToastInvoke,
  createShowLoadingInvoke,
  createHideLoadingInvoke,
  pushToastItem,
} from './toastCore.js'

const CONTAINER_ID = 'awd-toast-host'

const state = reactive({ items: [], loading: null })
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
    createApp(AwdToastHost, { state }).mount(el)
    hostMounted = true
  } catch (e) {
    console.warn('[toast] 宿主挂载失败:', e)
  }
  return hostMounted
}

function pushItem(opts) {
  if (!ensureHost()) return
  state.items = pushToastItem(state.items, { id: ++seq, ...opts })
}

function hideAll() {
  state.items = []
}

function setLoading(opts) {
  if (!ensureHost()) return
  state.loading = { id: ++seq, ...opts }
}

function clearLoading() {
  state.loading = null
}

let bridgeInstalled = false

/** 把 uni.showToast / hideToast / showLoading / hideLoading 转发到 AwdToast。只在 H5 入口调一次。 */
export function installUniToastBridge() {
  if (bridgeInstalled) return
  bridgeInstalled = true
  try {
    uni.addInterceptor('showToast', { invoke: createShowToastInvoke(pushItem) })
    uni.addInterceptor('hideToast', { invoke: createHideToastInvoke(hideAll) })
    uni.addInterceptor('showLoading', { invoke: createShowLoadingInvoke(setLoading) })
    uni.addInterceptor('hideLoading', { invoke: createHideLoadingInvoke(clearLoading) })
  } catch (e) {
    // 接管失败时退回 uni 原生弹层，至少功能还在
    console.warn('[toast] uni toast/loading 接管失败:', e)
  }
}
