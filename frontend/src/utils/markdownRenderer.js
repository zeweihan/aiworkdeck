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

/** 把 Markdown 渲染成 HTML 字符串。 */
export function renderMarkdown(text) {
  return md.render(text || '')
}

/** 只供测试断言用：确认拿到的是同一个、未被代理的实例。 */
export function markdownInstance() {
  return md
}
