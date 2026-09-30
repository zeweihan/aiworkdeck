// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// useAgentStream.js 带 @/ 别名与 uni 全局，node 直接 import 不进来：
// 读源码、剥掉全部 import、用 new Function 按形参名注入依赖，再整段跑真实 composable。
// 源码新增 import 时只需在下面 DEPS 表加一行（名字 + 默认实现），各用例不用动。
import { readFileSync } from 'node:fs'
import { ref, reactive, nextTick } from 'vue'
import { createProtocolTagRegex, decodeProtocolTags, decodeProtocolTagsIncremental } from '../../src/composables/agentTagProtocol.mjs'
import {
  applyInboxReceipt, applyInboxSnapshot, applyInputApplied, createInboxState,
  markInboxEvent, removeInboxItem, replaceInboxItem,
} from '../../src/composables/agentInboxState.mjs'
import { ASK_USER_KIND, decodeAttr, normalizeAskUserEvent } from '../../src/utils/askUserAnswer.mjs'
import { captureChatTimeline } from '../../src/components/AgentMessage/chatTimeline.mjs'
import { nextBubbleId } from '../../src/composables/bubbleId.js'
import { documentEditedFromProcesses } from '../../src/utils/useInDocumentVisibility.js'
import { isSameFileChange } from '../../src/utils/chatFileChange.js'
import { isAccountRequired, isCreditsRequired } from '../../src/utils/requireAccountCore.js'
import { isUserQuestionAwaiting } from '../../src/composables/awaitingInput.mjs'

const SOURCE = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

// 依赖清单：形参名 -> 默认实现。用例可用同名 overrides 覆盖。
const DEPS = {
  ref,
  reactive,
  nextTick,
  onUnmounted: () => {},
  getCurrentInstance: () => null,
  createProtocolTagRegex,
  decodeProtocolTags,
  decodeProtocolTagsIncremental,
  t: (key) => key,
  nextBubbleId,
  captureChatTimeline,
  documentEditedFromProcesses,
  isSameFileChange,
  createInboxState,
  applyInboxReceipt,
  applyInboxSnapshot,
  applyInputApplied,
  markInboxEvent,
  removeInboxItem,
  replaceInboxItem,
  getApiBaseUrl: () => 'http://test.local',
  getSessionId: () => 'test-session',
  getAgentInbox: async () => ({ items: [], runId: null, status: null }),
  updateAgentInboxItem: async () => null,
  deleteAgentInboxItem: async () => ({ items: [] }),
  getConversationMetadata: async () => null,
  ASK_USER_KIND,
  decodeAttr,
  normalizeAskUserEvent,
  isUserQuestionAwaiting,
  isAccountRequired,
  isCreditsRequired,
}

/**
 * 构造并返回 useAgentStream() 的结果。
 * overrides：按形参名覆盖依赖；特殊键 expose（字符串，如 'handleEvent, currentAssistantBubble'）
 * 会追加到 composable 返回对象里，暴露内部变量给用例。
 */
export function buildAgentStreamFactory(overrides = {}) {
  const { expose, ...depOverrides } = overrides
  let body = SOURCE.replace(/^import .*$/gm, '')
    .replace('export function useAgentStream()', 'function useAgentStream()')
  if (expose) body = body.replace('        bubbles,\n', `        bubbles, ${expose},\n`)
  const deps = { ...DEPS, ...depOverrides }
  const names = Object.keys(deps)
  const factory = new Function(...names, body + '\nreturn useAgentStream()')
  return factory(...names.map((n) => deps[n]))
}
