// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// chatClipboard.js — AI 对话面板里「复制」这颗按钮背后的两件事：拿什么、怎么放进剪贴板。
//
// 为什么单独一个文件：复制出现在三处（助手回答、工具卡的调用与输出、正文里的代码块），
// 三处都要「取纯文本 → setClipboardData → 成功才提示」这同一串动作。散在各组件里写三遍，
// 早晚会漂移成「有的复制到协议 XML、有的复制失败还提示已复制」。

import { t } from '@/i18n'
import { markdownToPlainText } from './markdownPlain.js'
import { PROTOCOL_TAGS } from '@/composables/agentTagProtocol.mjs'

/**
 * 协议标签的整标签形状（起始 / 闭合 / 自闭合都认），清单从 agentTagProtocol 取，
 * 不在这里另抄一份——那份与后端 AgentTagProtocol.TAGS 有对拍护栏。
 */
const PROTOCOL_TAG_RE = new RegExp(`<\\/?(?:${PROTOCOL_TAGS.join('|')})(?:\\s[^>]*)?\\/?>`, 'gi')

/**
 * 一条助手回答可以被粘进邮件 / 微信 / 另一份文书的那一份文本。
 *
 * <p>两步：先去掉协议标签，再做 Markdown 纯文本化。
 *
 * <p><b>协议标签这一步不能省</b>：`bubble.content` 正常情况下已经由解析器剥干净了，
 * 但有三条路会把标签原样留在里面——流式中途被打断（未闭合的 `<tool_code>` 连同参数
 * 留在缓冲里）、历史回灌时解析不了的旧格式、以及模型自己在正文里复述协议标签。
 * 用户按下复制那一刻不该赌这三条路都没发生：粘进合同里的 `<final>` 比少复制一段更难发现。
 *
 * <p>纯文本化与「插入当前文档」共用 {@link markdownToPlainText}：粘进 Word 的和插进
 * LOWA 的应该是同一份文字，两套剥离规则会让同一条回答在两个出口长得不一样。
 */
export function answerPlainText(content) {
  const raw = String(content == null ? '' : content)
  if (!raw.trim()) return ''
  return markdownToPlainText(raw.replace(PROTOCOL_TAG_RE, ''))
}

/**
 * 把一段文本放进剪贴板，成功才提示。
 *
 * <p>失败也要提示：桌面壳与浏览器都可能在非用户手势、无剪贴板权限时静默失败，
 * 不提示的话用户会以为复制成功，粘出来的是上一次的内容。
 *
 * @param text 要复制的文本，空白视为没有可复制的东西（返回 false，不提示也不调用系统接口）
 * @param successKey 成功提示的 i18n 键，默认 common.copied（「已复制」）
 * @returns 是否真的发起了复制
 */
export function copyToClipboard(text, successKey = 'common.copied') {
  const data = String(text == null ? '' : text)
  if (!data.trim()) return false
  uni.setClipboardData({
    data,
    success: () => uni.showToast({ title: t(successKey), icon: 'none' }),
    fail: () => uni.showToast({ title: t('chat.copyFailed'), icon: 'none' })
  })
  return true
}
