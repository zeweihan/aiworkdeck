// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 播放器键盘快捷键（规格 3.7）：keydown → 动作。只挂播放器根元素，不挂 window。
// 返回 null 表示不拦（调用方不得 preventDefault）；命中时调用方 preventDefault + stopPropagation。

export const RATE_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

export function isEditableTarget(el) {
  if (!el) return false
  if (el.isContentEditable) return true
  const tag = String(el.tagName || '').toUpperCase()
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}

/**
 * 媒体预览打开时，播放器该不该把焦点接过来（让空格/方向键立刻可用）。
 * 最常见的路径是从资源管理器点开：此刻焦点在带 tabindex 的文件树上，不接管的话
 * 空格/方向键全打到文件树，↑↓ 还会切走选中文件、把播放器卸掉——所以普通可聚焦元素要接管。
 * 不接管的只有「用户正在输入」：input/textarea/select/contenteditable，以及 iframe
 * （LOWA 编辑器等内嵌页，焦点在里面时外层 activeElement 就是 iframe 元素本身）。
 */
export function shouldGrabMediaFocus(activeEl) {
  if (!activeEl) return true
  if (isEditableTarget(activeEl)) return false
  return String(activeEl.tagName || '').toUpperCase() !== 'IFRAME'
}

/**
 * @returns null 或 { type, value? }
 *   type: toggle-play | seek-by(秒) | seek-to-ratio(0|1) | volume-by(0..1 小数) | toggle-mute
 *         | toggle-fullscreen | toggle-captions | rate-step(±1) | close-popover
 */
export function resolveShortcut(event, { kind, captionsAvailable } = {}) {
  if (!event) return null
  // 带 Ctrl/Cmd/Alt 的组合键属于工作台与系统（Cmd+F 查找、Cmd+K 等），一律不拦
  if (event.ctrlKey || event.metaKey || event.altKey) return null
  if (event.isComposing) return null
  if (isEditableTarget(event.target)) return null

  const key = event.key
  const code = event.code

  if (key === '<' || (event.shiftKey && code === 'Comma')) return { type: 'rate-step', value: -1 }
  if (key === '>' || (event.shiftKey && code === 'Period')) return { type: 'rate-step', value: 1 }

  if (key === ' ' || key === 'Spacebar' || code === 'Space') return { type: 'toggle-play' }

  switch (key) {
    case 'ArrowLeft': return { type: 'seek-by', value: -5 }
    case 'ArrowRight': return { type: 'seek-by', value: 5 }
    case 'ArrowUp': return { type: 'volume-by', value: 0.1 }
    case 'ArrowDown': return { type: 'volume-by', value: -0.1 }
    case 'Home': return { type: 'seek-to-ratio', value: 0 }
    case 'End': return { type: 'seek-to-ratio', value: 1 }
    case 'Escape':
    case 'Esc': return { type: 'close-popover' }
  }

  switch (typeof key === 'string' ? key.toLowerCase() : '') {
    case 'k': return { type: 'toggle-play' }
    case 'j': return { type: 'seek-by', value: -10 }
    case 'l': return { type: 'seek-by', value: 10 }
    case 'm': return { type: 'toggle-mute' }
    case 'f': return kind === 'audio' ? null : { type: 'toggle-fullscreen' }
    case 'c': return captionsAvailable ? { type: 'toggle-captions' } : null
  }
  return null
}

/** step ±1，钳在 RATE_STEPS 内；当前值不在档位上时取相邻档，非法值按 1x。 */
export function nextRate(current, step) {
  const cur = Number.isFinite(current) ? current : 1
  if (step > 0) {
    const up = RATE_STEPS.find(r => r > cur + 1e-9)
    return up === undefined ? RATE_STEPS[RATE_STEPS.length - 1] : up
  }
  if (step < 0) {
    const downs = RATE_STEPS.filter(r => r < cur - 1e-9)
    return downs.length ? downs[downs.length - 1] : RATE_STEPS[0]
  }
  return cur
}
