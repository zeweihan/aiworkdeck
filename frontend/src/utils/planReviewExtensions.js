// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 计划审阅的 CodeMirror 6 扩展（dev-board#1022）：改动行底色 + 左侧标记条、删除段小标记、
 * 批注高亮、选区旁「+」按钮。算什么画在哪全在 planReviewSpecs.js（纯函数、可单测），
 * 这里只负责翻译成装饰。
 *
 * 返回的 extensions 可以整体放进一个 Compartment、退出审阅态时 reconfigure([]) 卸载：
 * 状态全挂在 StateField 上，防抖计时器由 ViewPlugin 持有并在 destroy 里清掉，
 * 不往 view 或全局上留任何东西。
 */
import { StateField, StateEffect } from '@codemirror/state'
import { EditorView, Decoration, WidgetType, ViewPlugin, showTooltip } from '@codemirror/view'
import { buildDecorationSpecs, selectionSnapshot } from './planReviewSpecs.js'
import { PLAN_REVIEW_TOKENS } from './appTheme.js'

const RECOMPUTE_DEBOUNCE_MS = 150

class DeletedWidget extends WidgetType {
  constructor(count, text, label) {
    super()
    this.count = count
    this.text = text
    this.label = label
  }

  eq(other) {
    return other.count === this.count && other.text === this.text && other.label === this.label
  }

  toDOM() {
    const el = document.createElement('div')
    el.className = 'cm-review-deleted'
    el.title = this.text
    el.textContent = this.label
    return el
  }

  ignoreEvent() {
    return false
  }
}

const reviewTheme = EditorView.baseTheme({
  // 令牌值的来源是 appTheme.js 的 PLAN_REVIEW_TOKENS，这里只负责挂到编辑器根元素上
  '&': { ...PLAN_REVIEW_TOKENS.light },
  "html[data-theme='dark'] &": { ...PLAN_REVIEW_TOKENS.dark },
  '.cm-review-edited': {
    backgroundColor: 'var(--awd-review-edited-bg)',
    boxShadow: 'inset 3px 0 0 var(--awd-review-edited-bar)'
  },
  '.cm-review-deleted': {
    color: 'var(--awd-review-deleted-fg)',
    fontSize: '12px',
    padding: '0 8px'
  },
  '.cm-review-commented': {
    backgroundColor: 'var(--awd-review-comment-bg)'
  },
  '.cm-tooltip.cm-review-add-tip': {
    border: 'none',
    backgroundColor: 'transparent'
  },
  '.cm-review-add': {
    width: '22px',
    height: '22px',
    padding: '0',
    border: '1px solid var(--awd-border-strong)',
    borderRadius: '11px',
    backgroundColor: 'var(--awd-surface)',
    color: 'var(--awd-accent-text)',
    fontSize: '15px',
    lineHeight: '20px',
    cursor: 'pointer',
    boxShadow: 'var(--awd-shadow-sm)'
  }
})

/**
 * @param {object} opts
 * @param {() => string} opts.getBaseline 基线全文
 * @param {() => Array<{id,fromLine,toLine,quotedText,body}>} opts.getComments 当前批注
 * @param {(sel: {fromLine,toLine,quotedText}) => void} opts.onAddComment 点「+」时回调
 * @param {(count: number) => string} [opts.deletedLabel] 删除段标记文案（zh/en 由调用方查 locale）
 * @returns {{ extensions: import('@codemirror/state').Extension[], refresh: (view: EditorView) => void }}
 */
export function createReviewExtensions({ getBaseline, getComments, onAddComment, deletedLabel }) {
  const label = typeof deletedLabel === 'function' ? deletedLabel : (count) => `已删除 ${count} 行`
  const recompute = StateEffect.define()

  function build(state) {
    const doc = state.doc
    const specs = buildDecorationSpecs({
      baseline: String((getBaseline && getBaseline()) || ''),
      current: doc.toString(),
      comments: (getComments && getComments()) || []
    })
    const ranges = []
    for (const ln of specs.editedLines) {
      if (ln < 1 || ln > doc.lines) continue
      ranges.push(Decoration.line({ class: 'cm-review-edited' }).range(doc.line(ln).from))
    }
    for (const d of specs.deletions) {
      const atEnd = d.beforeLine > doc.lines
      const pos = atEnd ? doc.length : doc.line(Math.max(1, d.beforeLine)).from
      const widget = new DeletedWidget(d.count, d.text, label(d.count))
      ranges.push(Decoration.widget({ widget, block: true, side: atEnd ? 1 : -1 }).range(pos))
    }
    for (const c of specs.commentRanges) {
      if (!c.found) continue
      const fromLine = Math.max(1, Math.min(c.fromLine, doc.lines))
      const toLine = Math.max(fromLine, Math.min(c.toLine, doc.lines))
      const from = doc.line(fromLine).from
      const to = doc.line(toLine).to
      if (to <= from) continue
      ranges.push(Decoration.mark({
        class: 'cm-review-commented',
        attributes: { 'data-comment-id': String(c.id) }
      }).range(from, to))
    }
    return Decoration.set(ranges, true)
  }

  const decoField = StateField.define({
    create: (state) => build(state),
    update(value, tr) {
      if (tr.effects.some((e) => e.is(recompute))) return build(tr.state)
      return tr.docChanged ? value.map(tr.changes) : value
    },
    provide: (f) => EditorView.decorations.from(f)
  })

  function addButtonTooltip(state) {
    const sel = state.selection.main
    if (sel.empty) return null
    return {
      pos: sel.head,
      above: sel.head === sel.from,
      strictSide: true,
      arrow: false,
      create(view) {
        const dom = document.createElement('button')
        dom.type = 'button'
        dom.className = 'cm-review-add'
        dom.textContent = '+'
        const wrap = document.createElement('div')
        wrap.className = 'cm-review-add-tip'
        wrap.appendChild(dom)
        // 按下时别让编辑器失焦丢选区，点击时再取快照
        dom.addEventListener('mousedown', (e) => e.preventDefault())
        dom.addEventListener('click', (e) => {
          e.preventDefault()
          const snap = selectionSnapshot(view.state)
          if (snap && onAddComment) onAddComment(snap)
        })
        return { dom: wrap }
      }
    }
  }

  const tooltipField = StateField.define({
    create: addButtonTooltip,
    update(value, tr) {
      if (!tr.docChanged && !tr.selection) return value
      return addButtonTooltip(tr.state)
    },
    provide: (f) => showTooltip.compute([f], (state) => state.field(f))
  })

  // 打字时装饰先随改动平移，停笔 150ms 后按最新全文重算
  const debounce = ViewPlugin.fromClass(class {
    constructor(view) {
      this.view = view
      this.timer = null
    }

    update(u) {
      if (!u.docChanged) return
      clearTimeout(this.timer)
      this.timer = setTimeout(() => {
        this.timer = null
        this.view.dispatch({ effects: recompute.of(null) })
      }, RECOMPUTE_DEBOUNCE_MS)
    }

    destroy() {
      clearTimeout(this.timer)
    }
  })

  return {
    extensions: [decoField, tooltipField, debounce, reviewTheme],
    refresh(view) {
      if (view) view.dispatch({ effects: recompute.of(null) })
    }
  }
}
