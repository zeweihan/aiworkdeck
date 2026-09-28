// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 付费转写确认的接线（dev-board#968）：把档位、余额、单价、应用内对话框、翻译注入
// paidTranscribeConfirm.js 的纯逻辑。所有会提交转写的入口（资源管理器右键「转写」、
// 会议录音面板的「开始转写 / 重试转写」、结束录音时的自动转写）提交前都经这里。
import {
  getMeetingRecordings, getPlatformServices, getPlatformServiceRemote, getAccountBalance,
} from '@/services/api.js'
import { showDialog } from '@/utils/dialog.js'
import { t } from '@/i18n'
import { host } from '@/services/host.js'
import { confirmPaidTranscription as confirmCore } from '@/utils/paidTranscribeConfirm.js'

/**
 * 生效档位 + 这份音频已有记录的状态。
 * **优先读会议列表**（项目成员可读，后端带 tier）：团队服务器上的非管理员读不到机器级的
 * /api/platform-services，按「读不到照样问」会被误问一句扣 Credits，而服务器模式根本没有平台档。
 * 列表没带 tier（旧后端）才退回 /api/platform-services。
 */
async function getContext(opts) {
  let existingStatus = null
  let configured
  if (opts.projectId != null) {
    try {
      const res = await getMeetingRecordings(opts.projectId)
      if (res && opts.audioFileId != null) {
        const m = (res.meetings || []).find((x) => String(x.audioFileId) === String(opts.audioFileId))
        existingStatus = m ? m.status : null
      }
      // configured=false 时后端不会提交（registerFile / finish 同一道闸）
      if (res && typeof res.configured === 'boolean') configured = res.configured
      if (res && res.tier) return { provider: res.tier, platformAvailable: true, existingStatus, configured }
    } catch (e) { /* 退回下面 */ }
  }
  const s = (await getPlatformServices()) || {}
  const asr = (s.services || []).find((x) => x.service === 'asr')
  if (!asr) return existingStatus || configured === false
    ? { provider: null, platformAvailable: true, existingStatus, configured } : null
  return { provider: asr.provider || null, platformAvailable: s.platformAvailable !== false, existingStatus, configured }
}

// 与工作台顶栏 Credits chip 同一个端点；未连接或官网不可达时不显示余额，不拿 0 冒充
async function getBalanceCents() {
  const d = await getAccountBalance()
  if (!d || !d.connected || d.available === false) return null
  const cents = Number(d.balanceCents)
  return Number.isFinite(cents) ? cents : null
}

async function getAsrPrice() {
  const r = await getPlatformServiceRemote()
  return (r && r.asrPrice) || null
}

/**
 * @param {{durationMs?: number, projectId?: string|number, audioFileId?: string|number}} [opts]
 * @returns {Promise<boolean>} true = 用户确认（或确定不扣 Credits），可以提交
 */
export function confirmPaidTranscription(opts) {
  const o = opts || {}
  return confirmCore(o, {
    getContext: () => getContext(o),
    getBalanceCents,
    getAsrPrice,
    // 本机转写引擎只在桌面壳里有（浏览器版没有 host.model，给「改用本机转写」是错的指路）
    localPossible: () => !!host.model,
    showDialog,
    t,
  })
}
