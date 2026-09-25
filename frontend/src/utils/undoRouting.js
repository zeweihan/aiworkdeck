// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 菜单栏「编辑 > 撤销/重做」在渲染层落到哪儿（v0.49.0 BUG-30）。
//
// 文档标签激活时主进程把撤销/重做从 role 改成转发（desktop/main/app-menu.js 的
// routeUndoRedo）——role 只认浏览器原生编辑历史，对画布渲染的 LOWA 引擎无效。但转发
// 过来**不能一律撤销文档**：文档标签开着时律师照样会在 AI 输入框、查找替换、批注表单、
// 重命名框里打字，⌘Z 在那里必须撤销那个输入框（J1 复核否决第一版的理由）。
// 所以按 document.activeElement 分三路：
//   'native' 可编辑元素（input/textarea/contenteditable）或没有文档可撤销 → execCommand
//   'editor' 焦点在编辑器画布的 webview/iframe 上（或焦点无主、文档标签开着）→ .uno:Undo
//   'frame'  焦点在别的 webview/iframe（draw.io 等）→ 让那个客体做原生撤销，等同原 role

const NON_TEXT_INPUT = new Set(['button', 'checkbox', 'color', 'file', 'hidden', 'image', 'radio', 'range', 'reset', 'submit'])
// 编辑器画布（webview/iframe）的宿主容器，见 components/LibreOfficeEditor.vue 根节点
export const EDITOR_HOST_SELECTOR = '.libre-editor-wrapper'

/** 这个元素是不是一个自己有编辑历史的文本输入。 */
export function isEditableElement(el) {
  if (!el || el.nodeType !== 1) return false
  const tag = String(el.tagName || '').toUpperCase()
  if (tag === 'TEXTAREA') return !el.readOnly && !el.disabled
  if (tag === 'INPUT') {
    const type = String(el.getAttribute('type') || 'text').toLowerCase()
    return !NON_TEXT_INPUT.has(type) && !el.readOnly && !el.disabled
  }
  if (el.isContentEditable === true) return true
  // jsdom 等不实现 isContentEditable 的环境按属性判
  return typeof el.closest === 'function' && !!el.closest('[contenteditable]:not([contenteditable="false"])')
}

/**
 * @param {Element|null} active document.activeElement
 * @param {{ docTab?: boolean }} [opts] 当前是不是文档标签（主进程只在文档标签激活时才转发）
 * @returns {'native'|'editor'|'frame'}
 */
export function resolveUndoTarget(active, { docTab = true } = {}) {
  if (isEditableElement(active)) return 'native'
  const tag = active && active.tagName ? String(active.tagName).toUpperCase() : ''
  if (tag === 'WEBVIEW' || tag === 'IFRAME') {
    const inEditor = typeof active.closest === 'function' && !!active.closest(EDITOR_HOST_SELECTOR)
    return inEditor ? 'editor' : 'frame'
  }
  return docTab ? 'editor' : 'native'
}

/**
 * 执行一次菜单撤销/重做。
 * @param {'undo'|'redo'} verb
 * @param {{ doc?: Document, docTab?: boolean, runEditor: Function }} env runEditor：发给编辑器（.uno:Undo/Redo）
 * @returns {'native'|'editor'|'frame'} 实际落点（测试与日志用）
 */
export function runMenuUndoRedo(verb, { doc, docTab = true, runEditor } = {}) {
  const d = doc || (typeof document !== 'undefined' ? document : null)
  const active = d ? d.activeElement : null
  const target = resolveUndoTarget(active, { docTab })
  try {
    if (target === 'editor') {
      if (typeof runEditor === 'function') runEditor(verb)
    } else if (target === 'frame') {
      // <webview> 自带 undo()/redo()（转给客体 webContents）；同源 iframe 走它自己的 document
      if (typeof active[verb] === 'function') active[verb]()
      else if (active.contentDocument && typeof active.contentDocument.execCommand === 'function') active.contentDocument.execCommand(verb)
    } else if (d && typeof d.execCommand === 'function') {
      d.execCommand(verb)
    }
  } catch (e) { /* 跨源 iframe / 已销毁的客体：什么都不做，别让菜单点击抛到外面 */ }
  return target
}
