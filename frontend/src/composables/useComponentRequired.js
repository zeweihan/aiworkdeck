// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI 对话里的「需要下载组件」闸（设计 §4.2）。
//
// 后端工具（PptxTools / PdfTools）在服务打不通且 pack 未装时发 client_action
// component_required，前端在 ChatInterface 的 onClientAction 接缝就地拦下——
// 它不是编辑器命令，透到 libreofficeExecutorClient 的 EDITOR_ACTIONS 白名单
// 只会换来一句 "Unknown action"。
//
// 装完**自动重发原消息**：让用户装完组件还得把刚才那句话再打一遍，
// 等于把失败的代价原样转嫁给他。
// 例外（dev-board#581）：用户点了「后台下载」收起卡片、继续用对话之后，装完时
// 对话里已经有了新消息或正在生成——这时重发会插进用户正在进行的事情里，
// 只提示「组件已就绪，可以重试」（判据见 shouldAutoResend）。
//
// 依赖同样全部注入（与 useOptionalComponents 同源），单测不需要真后端与 Electron。
// 这里用相对路径 import 而不是 @/ 别名：node --test 不认那个别名。

import { PACK_LOCALE_KEY } from './useOptionalComponents.js'

/**
 * 装完要不要自动重发原消息。
 * - 对话组件已卸载（离开了工作台）：不重发，没有地方可发；
 * - 卡片一直在前台：照旧重发；
 * - 已转后台：拦截之后没有新的用户消息、且当前不在生成中，才重发。
 */
export function shouldAutoResend({ alive, backgrounded, streaming, userCountAtGate, userCountNow }) {
  if (!alive) return false
  if (!backgrounded) return true
  return !streaming && userCountNow === userCountAtGate
}

export function createComponentRequiredHandler(deps) {
  const inFlight = new Set()

  function itemFromPayload(p) {
    return {
      packId: p.packId,
      service: p.service,
      localeKey: PACK_LOCALE_KEY[p.packId] || p.packId,
      installed: false,
      downloadBytes: (p.sizeMb || 0) * 1024 * 1024,
      unpackedBytes: 0,
      modelId: p.modelId || null,
      modelInstalled: false,
      modelBytes: 0,
      featureKeys: p.features || [],
      phase: 'idle',
      percent: 0,
      error: '',
      selected: false,
    }
  }

  async function onAction(p) {
    if (!p || !p.packId) return { installed: false, resent: false }
    // 工具可能在同一轮里连报两次（check_service 之后紧跟 generate）：只处理一次。
    // duplicate 标给调用方：这一次什么都没做，不能据此收起第一次挂出来的卡片
    if (inFlight.has(p.packId)) return { installed: false, resent: false, duplicate: true }
    inFlight.add(p.packId)
    try {
      // 换成应用级下载管理里的规范 item：别的入口正在下载时，卡片上显示的是同一份进度
      const raw = itemFromPayload(p)
      const item = deps.adopt ? deps.adopt(raw) : raw
      // 重发的是拦截这一刻的原消息；装完时对话里最新的一条可能已经是用户后来发的
      const text = deps.lastUserMessage()
      const mark = deps.mark ? deps.mark(item) : null

      if (deps.isInstalling && deps.isInstalling(item.packId)) {
        // 已经在别的入口下载中：不再问一遍，直接挂上进度
        if (deps.attach) deps.attach(item)
      } else {
        await deps.fillSizes(item)          // sizeMb 为 0（后端没缓存）时补一次
        if (!(await deps.confirm(item))) return { installed: false, resent: false }
      }

      const ok = await deps.installOne(item)
      if (!ok) {
        deps.toast(item.error || '', item)
        return { installed: false, resent: false }
      }
      if (!text) return { installed: true, resent: false }
      if (deps.shouldResend && !deps.shouldResend(mark, item)) {
        if (deps.readyNotice) deps.readyNotice(item)
        return { installed: true, resent: false }
      }
      await deps.resend(text, item)
      return { installed: true, resent: true }
    } finally {
      inFlight.delete(p.packId)
    }
  }

  return { itemFromPayload, onAction }
}
