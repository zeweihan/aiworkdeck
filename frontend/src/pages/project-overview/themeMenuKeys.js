// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 顶栏外观下拉的键位 → 动作（v0.49.0 BUG-53）。纯函数，不碰 DOM，便于无头测。
// 形制按 WAI-ARIA menu 模式：上下键循环、Home/End 到头尾、Enter/空格选中、
// Esc 关闭（焦点回触发器）、Tab 关闭且不拦默认的焦点移动。

export function themeMenuKeyAction(key, index, count) {
  const n = Number(count) || 0
  const i = Number(index) || 0
  switch (key) {
    case 'ArrowDown': return n ? { type: 'move', index: (i + 1) % n } : null
    case 'ArrowUp': return n ? { type: 'move', index: (i - 1 + n) % n } : null
    case 'Home': return n ? { type: 'move', index: 0 } : null
    case 'End': return n ? { type: 'move', index: n - 1 } : null
    case 'Enter':
    case ' ':
    case 'Spacebar':
      return { type: 'pick', index: i }
    case 'Escape':
    case 'Esc':
      return { type: 'close' }
    case 'Tab': return { type: 'close', keepDefault: true }
    default: return null
  }
}
