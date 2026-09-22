// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import MarkdownIt from 'markdown-it'

/**
 * 全仓唯一的 markdown-it 实例（dev-board#750）。
 *
 * **必须是模块级单例，绝不能放进组件的 `data()`。** Vue 3 的 Options API 会把 `data()`
 * 返回的对象整个 `reactive()` 一遍，markdown-it 实例也不例外；此后解析过程中对
 * `md.options` / `md.block` / `md.inline` / `md.renderer` 的每一次内部访问都要过 Proxy
 * trap，而且因为 render 是在 computed getter 里跑的，每次访问还要登记一次依赖。
 * 本机实测（markdown-it 14 + @vue/reactivity，8450 字中文法律文书）：
 * 普通实例 0.545ms / 响应式实例 2.028ms / 在 computed 里 2.377ms —— **慢 3.7~4.4 倍**。
 * 流式回答每个 token 重渲一次，一篇 8000 字的回答光解析就要烧掉约 2 秒主线程。
 *
 * 单例是安全的：markdown-it 的 render 不持有跨次调用的状态（每次 render 新建 state），
 * 配置在这里一次性定好，没有任何调用方需要不同配置。
 */
const md = new MarkdownIt({
  // 渲染结果直接进 v-html，而内容来自他人上传的 .md 与模型输出，
  // 放行原始 HTML 等于存储型 XSS，故禁用
  html: false,
  linkify: true,
  typographer: true
})

// 裸 <table> 没有滚动容器，宽表格会被上游面板的 overflow:hidden 直接裁掉且不出滚动条，
// 这里包一层可横向滚动的 div（dev-board#467）
md.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
md.renderer.rules.table_close = () => '</table></div>'

// 代码块右上角的复制键（dev-board#790）。AI 给的条款、模板、脚本经常整块落在代码块里，
// 而流式渲染期 MarkdownPreview 每帧重写整段 v-html，手工框选会被当场清掉——
// 代码块是最需要一颗按钮的地方。
//
// 按钮只是标记：真正的复制由 MarkdownPreview 的事件委托做（读同一个 wrapper 里 <pre> 的
// textContent）。**不把代码原文塞进 data-* 属性**——那要再转义一遍，且会让整段 HTML 翻倍。
//
// 按钮文字由调用方随 env 传进来（renderMarkdown 的第二个参数），**本模块刻意不 import i18n**：
// 它被 node --test 直接 import（tests/markdown-table/*），而 `@/` 别名只有 vite 认得，
// 加一个 import 就会让那两份用例整个跑不起来。没给 copyLabel 就不渲染按钮——
// 兜底成一句硬编码英文会在英文以外的界面里露出来，宁可不出这颗按钮。
const wrapWithCopy = (html, env) => {
  const label = env && typeof env.copyLabel === 'string' ? env.copyLabel.trim() : ''
  if (!label) return html
  return `<div class="md-code-block"><button type="button" class="md-copy-btn" data-md-copy>${md.utils.escapeHtml(label)}</button>${html}</div>`
}
const defaultFence = md.renderer.rules.fence
const defaultCodeBlock = md.renderer.rules.code_block
md.renderer.rules.fence = (tokens, idx, options, env, self) =>
  wrapWithCopy((defaultFence || self.renderToken.bind(self))(tokens, idx, options, env, self), env)
md.renderer.rules.code_block = (tokens, idx, options, env, self) =>
  wrapWithCopy((defaultCodeBlock || self.renderToken.bind(self))(tokens, idx, options, env, self), env)

/**
 * 把 Markdown 渲染成 HTML 字符串。
 * @param text 源文本
 * @param env markdown-it 的 env。目前只认 copyLabel：给了才在代码块右上角渲染复制键。
 */
export function renderMarkdown(text, env) {
  return md.render(text || '', env || {})
}

/** 只供测试断言用：确认拿到的是同一个、未被代理的实例。 */
export function markdownInstance() {
  return md
}
