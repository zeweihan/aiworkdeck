// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 连接账户之前的两项同意（登录后置设计 2026-09-29 §5.6，dev-board#1046）。
//
// 原来绑在解锁页「登录成功」那一刻（unlock.vue 的 completeSetup：协议版本 + submitWizard 带
// crossBorderConsent）。登录后置之后首启初始化归后端（DataInitializer 把供应商默认成官方通道），
// 这里只剩「记下同意」这一件事，由**所有连接账户的入口**共用：登录弹层、设置页粘 Key。
//
// 为什么两个入口都要记跨境同意：后端默认供应商就是官方通道，而跨境闸只在「切到官方通道」
// 那一刻把关——默认值不经过那一刻。连上账户就是内容开始能出境的时点，所以同意必须落在连接之前。
// 两项都绝不预勾选（预先勾选的同意在个保法下无效），勾选框在各自的界面上。

import { acceptLegalAgreement, saveAdminConfig } from '@/services/api.js'

// 《服务条款》《隐私政策》组合版本。协议实质内容变更时 +1 日期，
// 后端只记录「哪个版本在何时被同意过」（legal.userAgreement.*），不据此设闸。
export const AGREEMENT_VERSION = '2026-08-27'

/**
 * 记下两项同意。失败不拦路（与原 completeSetup 同一口径）：设置页 AI 分区仍能补记，
 * 而为一次记录失败把人挡在登录门外不划算。
 */
export async function recordAccountConsents() {
  try {
    await acceptLegalAgreement(AGREEMENT_VERSION)
  } catch (e) {
    console.warn('记录协议同意失败（忽略）:', e && e.message)
  }
  try {
    // 只带 crossBorderConsent：toSettingsUpdates 跳过 null 字段，供应商等其余设置一个都不动
    await saveAdminConfig({ ai: { crossBorderConsent: true } })
  } catch (e) {
    console.warn('记录跨境传输同意失败（可在 AI 设置中补记）:', e && e.message)
  }
}
