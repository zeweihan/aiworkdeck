// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「刷新后回到上次那段对话」的持久化（dev-board#779 K7④）。
//
// 病灶（2026-09-22 实测 t4-after-reload.png）：律师在 AI 面板里正聊着，打断一次、
// 刷新一次，回来就是一段空会话——刚才那段上下文还在服务器上，但界面上没有任何
// 线索指回去，只能自己去历史抽屉里翻。AI 面板默认收起、ChatInterface 挂在 v-if 上，
// 页面重建时那条 conversationId 只活在组件内存里，刷新即丢。
//
// 键**按项目分**：换个案卷当然要换一段对话，不分项目就会出现「进 A 项目打开的是
// B 项目那段会话」——比空会话更糟。这跟 awd_sidebar_collapsed / awd_panel_docks
// 那种「本机使用习惯、跟着人走」的全局键不是一回事。
//
// 零依赖纯函数（同 pages/project-overview/sidebarCollapse.js 的先例）：写入方是
// ChatInterface（会话 id 归它所有——新会话是 handleSubmit 现造的），读取方是
// 工作台页（它才有页面生命周期和 loadHistoryChat），两边必须是同一个键，
// 各写一份字面量迟早漂移。`storage` 是 uni 的 get/set/removeStorageSync 接口子集。

const PREFIX = 'awd_last_conversation_'

const keyOf = (projectId) => {
  const id = String(projectId == null ? '' : projectId).trim()
  return id ? PREFIX + id : ''
}

/**
 * 读取该项目上次打开的会话 id，没有则返回空串。
 * @param {{ getStorageSync: (key: string) => any }} storage
 * @param {string|number} projectId
 * @returns {string}
 */
export function loadLastConversation(storage, projectId) {
  const key = keyOf(projectId)
  if (!key) return ''
  try {
    const value = storage.getStorageSync(key)
    return typeof value === 'string' ? value : ''
  } catch (e) {
    return ''
  }
}

/**
 * 记住该项目当前打开的会话 id。传空（新对话尚未发出第一条消息）即清除——
 * 用户刚点过「新对话」，刷新后再把旧会话拽回来是违背他刚表达的意图。
 * @param {{ setStorageSync: Function, removeStorageSync?: Function }} storage
 * @param {string|number} projectId
 * @param {string} conversationId
 */
export function saveLastConversation(storage, projectId, conversationId) {
  const key = keyOf(projectId)
  if (!key) return
  try {
    if (conversationId) storage.setStorageSync(key, String(conversationId))
    else if (typeof storage.removeStorageSync === 'function') storage.removeStorageSync(key)
    else storage.setStorageSync(key, '')
  } catch (e) {
    // 存储不可用（隐私模式/配额满）时静默：恢复是便利，不是功能前提
  }
}
